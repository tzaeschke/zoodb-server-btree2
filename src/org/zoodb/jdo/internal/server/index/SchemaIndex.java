/*
 * Copyright 2009-2013 Tilmann Z�schke. All rights reserved.
 * 
 * This file is part of ZooDB.
 * 
 * ZooDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ZooDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ZooDB.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * See the README and COPYING files for further information. 
 */
package org.zoodb.jdo.internal.server.index;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;

import javax.jdo.JDOFatalDataStoreException;
import javax.jdo.JDOUserException;

import org.zoodb.jdo.internal.Node;
import org.zoodb.jdo.internal.ZooClassProxy;
import org.zoodb.jdo.internal.ZooClassDef;
import org.zoodb.jdo.internal.ZooFieldDef;
import org.zoodb.jdo.internal.server.DiskAccessOneFile;
import org.zoodb.jdo.internal.server.StorageChannel;
import org.zoodb.jdo.internal.server.StorageChannelInput;
import org.zoodb.jdo.internal.server.StorageChannelOutput;
import org.zoodb.jdo.internal.server.index.PagedPosIndex.ObjectPosIteratorMerger;
import org.zoodb.jdo.internal.util.PrimLongMapLI;
import org.zoodb.jdo.internal.util.Util;

/**
 * Schema Index. This class manages the indices in the database. The indices are stored separately
 * from the schemata. Since schemas are not objects, they are referenced only by pageId, which
 * changes every time that an index changes. To avoid rewriting all schemata every time the indices
 * change, this class was introduced as a compressed version of the schemata. This should avoid
 * unnecessary page writes for rewriting the schemata. 
 * 
 * Structure
 * =========
 * For each schema, we store a list of indices for all fields that are indexed. This list is 
 * compatible only with the latest version of the schema. Field-indices from older versions are
 * removed (or move to the latest version, if they still exist).
 * 
 * The pos-indices are different. We have one pos-index for each version of a schema. This is 
 * necessary in the case that a field+index are added. A query that matches the default value
 * of the new field should return also all objects that have not been evolved yet (lazy evolution).
 * This is only possible if we maintain list of objects separately for each applicable schema 
 * version.
 * The pos-indices are sorted such that the newest version is at the beginning of the list.
 * 
 * 
 * @author ztilmann
 *
 */
public class SchemaIndex {

    //This maps the schemaId (not the OID!) to the SchemaIndexEntry
	private final PrimLongMapLI<SchemaIndexEntry> schemaIndex = 
		new PrimLongMapLI<SchemaIndexEntry>();
	private int pageId = -1;
	private final StorageChannel file;
	private final StorageChannelOutput out;
	private final StorageChannelInput in;
	private boolean isDirty = false;

	private static class FieldIndex {
	    //THis is the unique fieldId which is maintained throughout different versions of the field
		private long fieldId;
		private boolean isUnique;
		private FTYPE fType;
		private int page;
		private AbstractPagedIndex index;
	}

	private enum FTYPE {
		LONG(8, Long.TYPE, "long"),
		INT(4, Integer.TYPE, "int"),
		SHORT(2, Short.TYPE, "short"),
		BYTE(1, Byte.TYPE, "byte"),
		DOUBLE(8, Double.TYPE, "double"),
		FLOAT(4, Float.TYPE, "float"),
		CHAR(2, Character.TYPE, "char"), 
		STRING(8, null, "java.lang.String");
		private final int len;
		private final Type type;
		private final String typeName;
		private FTYPE(int len, Type type, String typeName) {
			this.len = len;
			this.type = type;
			this.typeName = typeName;
		}
		static FTYPE fromType(String typeName) {
			for (FTYPE t: values()) {
				if (t.typeName.equals(typeName)) {
					return t;
				}
			}
			throw new JDOUserException("Type is not indexable: " + typeName);
		}
	}
	
	/**
	 * Do not store classes here. On the server, the class may not be available.
	 * 
	 * Otherwise it would be nice, because comparison of references to classes is faster than
	 * Strings, and references require much less space. Then again, there are few schema classes,
	 * so space is not a problem. 
	 */
	public class SchemaIndexEntry {
		private final long schemaId;
		private long[] schemaOids;
		//Do not store classes here! See above.
		//We also do not store the class name, as it uses a lot of space, especially since
		//we do not return pages to FSM except the last one.
		private int[] objIndexPages;
		private transient PagedPosIndex[] objIndex;
		private ArrayList<FieldIndex> fieldIndices = new ArrayList<FieldIndex>();
		private transient ZooClassDef classDef;
		
		/**
		 * Constructor for reading index.
		 */
		private SchemaIndexEntry(StorageChannelInput in) {
		    schemaId = in.readLong();
		    int nVersion = in.readShort();
            schemaOids = new long[nVersion];
            for (int i = 0; i < nVersion; i++) {
                schemaOids[i] = in.readLong();
            }
			objIndexPages = new int[nVersion];
			for (int i = 0; i < nVersion; i++) {
			    objIndexPages[i] = in.readInt();
			}
		    int nF = in.readShort();
		    for (int i = 0; i < nF; i++) {
		    	FieldIndex fi = new FieldIndex();
		    	fieldIndices.add(fi);
		    	fi.fieldId = in.readLong();
		    	fi.fType = FTYPE.values()[in.readByte()];
		    	fi.isUnique = in.readBoolean();
		    	fi.page = in.readInt();
		    }
		}
		
		/**
		 * Constructor for creating new Index.
		 * @param id
		 * @param cName
		 * @param schPage
		 * @param schPageOfs
		 * @param raf
		 * @param def 
		 * @throws IOException 
		 */
		private SchemaIndexEntry(StorageChannel file, ZooClassDef def) {
			this.schemaId = def.getSchemaId();
			this.schemaOids = new long[1];
			this.schemaOids[0] = def.getOid();
			this.objIndex = new PagedPosIndex[1];
			this.objIndex[0] = PagedPosIndex.newIndex(file);
			this.objIndexPages = new int[1];
			this.classDef = def;
		}
		
		private void write(StorageChannelOutput out) {
		    out.writeLong(schemaId);
		    out.writeShort((short) schemaOids.length);
		    for (long oid: schemaOids) {
		        out.writeLong(oid);
		    }
		    for (int page: objIndexPages) {
		        out.writeInt(page);  //no data page yet
		    }
		    out.writeShort((short) fieldIndices.size());
		    for (FieldIndex fi: fieldIndices) {
		    	out.writeLong(fi.fieldId);
		    	out.writeByte((byte) fi.fType.ordinal());
		    	out.writeBoolean(fi.isUnique);
		    	out.writeInt(fi.page);
		    }
		}

		/**
		 * @return The pos-index for the latest schema version
		 */
        public PagedPosIndex getObjectIndexLatestSchemaVersion() {
            // lazy loading
            if (objIndex[objIndex.length-1] == null) {
                objIndex[objIndex.length-1] = 
                        PagedPosIndex.loadIndex(file, objIndexPages[objIndex.length-1]);
            }
            return objIndex[objIndex.length-1];
        }

        /**
         * 
         * @return Pos-indices for all schema versions
         */
        public ObjectPosIteratorMerger getObjectIndexIterator() {
            // lazy loading
            ObjectPosIteratorMerger ret = new ObjectPosIteratorMerger(); 
            for (int i = 0; i < objIndex.length; i++) {
                if (objIndex[i] == null) {
                    objIndex[i] = PagedPosIndex.loadIndex(file, objIndexPages[i]);
                }
                ret.add(objIndex[i].iteratorObjects());
            }
            return ret;
        }

		public AbstractPagedIndex defineIndex(ZooFieldDef field, boolean isUnique) {
			//double check
			if (!field.isPrimitiveType() && !field.isString()) {
				throw new JDOUserException("Type can not be indexed: " + field.getTypeName());
			}
			for (FieldIndex fi: fieldIndices) {
				if (fi.fieldId == field.getFieldSchemaId()) {
					throw new JDOUserException("Index is already defined: " + field.getName());
				}
			}
			FieldIndex fi = new FieldIndex();
			fi.fieldId = field.getFieldSchemaId();
			fi.fType = FTYPE.fromType(field.getTypeName());
			fi.isUnique = isUnique;
			field.setIndexed(true);
			field.setUnique(isUnique);
			if (isUnique) {
				fi.index = new PagedUniqueLongLong(file);
			} else {
				fi.index = new PagedLongLong(file);
			}
			fieldIndices.add(fi);
			return fi.index;
		}

		public boolean removeIndex(ZooFieldDef field) {
			Iterator<FieldIndex> iter = fieldIndices.iterator();
			while (iter.hasNext()) {
				FieldIndex fi = iter.next(); 
				if (fi.fieldId == field.getFieldSchemaId()) {
					iter.remove();
					fi.index.clear();
					field.setIndexed(false);
					return true;
				}
			}
			return false;
		}

		public AbstractPagedIndex getIndex(ZooFieldDef field) {
			for (FieldIndex fi: fieldIndices) {
				if (fi.fieldId == field.getFieldSchemaId()) {
					if (fi.index == null) {
						if (fi.isUnique) {
							fi.index = new PagedUniqueLongLong(file, fi.page);
						} else {
							fi.index = new PagedLongLong(file, fi.page);
						}
					}
					return fi.index;
				}
			}
			return null;
		}

		public ArrayList<AbstractPagedIndex> getIndices() {
			ArrayList<AbstractPagedIndex> indices = new ArrayList<AbstractPagedIndex>();
			for (FieldIndex fi: fieldIndices) {
				indices.add(fi.index);
			}
			return indices;
		}

		public boolean isUnique(ZooFieldDef field) {
			for (FieldIndex fi: fieldIndices) {
				if (fi.fieldId == field.getFieldSchemaId()) {
					return fi.isUnique;
				}
			}
			throw new IllegalArgumentException("Index not found for " + field.getName());
		}

		/**
		 * 
		 * @return True if any indices were written.
		 */
		private boolean writeAttrIndices() {
			boolean dirty = false;
			for (FieldIndex fi: fieldIndices) {
				//is index loaded?
				if (fi.index != null && fi.index.isDirty()) {
					fi.page = fi.index.write();
					dirty = true;
				}
			}
			return dirty;
		}

		public ZooClassDef getClassDef() {
			return classDef;
		}

        public void addVersion(ZooClassDef defNew) {
            Arrays.copyOf(objIndexPages, objIndexPages.length + 1);
            Arrays.copyOf(objIndex, objIndex.length + 1);
            objIndex[objIndex.length-1] = PagedPosIndex.newIndex(file);
            //remove indexes for deleted fields
            //add indices for new indexed fields...(?) Can we know that here? Will that happen later via an operation?
            System.err.println("FIXME: clean up field-indices");
        }

        public PagedPosIndex[] getObjectIndexes() {
            return objIndex;
        }
	}

	public SchemaIndex(StorageChannel file, int indexPage1, boolean isNew) {
		this.isDirty = isNew;
		this.file = file;
		this.in = file.getReader(true);
		this.out = file.getWriter(true);
		this.pageId = indexPage1;
		if (!isNew) {
			readIndex();
		}
	}
	
	private void readIndex() {
		in.seekPageForRead(pageId);
		int nIndex = in.readInt();
		for (int i = 0; i < nIndex; i++) {
			SchemaIndexEntry entry = new SchemaIndexEntry(in);
			schemaIndex.put(entry.schemaId, entry);
		}
	}

	
	public int write() {
		//write the indices
		for (SchemaIndexEntry e: schemaIndex.values()) {
		    //for (PagedPosIndex oi: e.objIndex) {
		    for (int i = 0; i < e.objIndex.length; i++) {
		        PagedPosIndex oi = e.objIndex[i];
    			if (oi != null) {
    				int p = oi.write();
    				if (p != e.objIndexPages[i]) {
    					markDirty();
    				}
    				e.objIndexPages[i] = p;
    			}
		    }
			//write attr indices
			if (e.writeAttrIndices()) {
				markDirty();
			}
		}

		if (!isDirty()) {
			return pageId;
		}

		//now write the index directory
		//we can do this only afterwards, because we need to know the pages of the indices
		pageId = out.allocateAndSeekAP(pageId, -1);

		//TODO we should use a PagedObjectAccess here. This means that we treat SchemaIndexEntries 
		//as objects, but would also allow proper use of FSM for them. 
		
		//number of indices
		out.writeInt(schemaIndex.size());

		//write the index directory
		for (SchemaIndexEntry e: schemaIndex.values()) {
			e.write(out);
		}

		out.flush();
		markClean();

		return pageId;
	}

	public SchemaIndexEntry getSchema(ZooClassDef def) {
		return schemaIndex.get(def.getSchemaId());
	}

	public Collection<SchemaIndexEntry> getSchemata() {
		return Collections.unmodifiableCollection(schemaIndex.values());
	}

    protected final boolean isDirty() {
        return isDirty;
    }
    
	protected final void markDirty() {
		isDirty = true;
	}
	
	protected final void markClean() {
		isDirty = false;
	}
		

	public void refreshSchema(ZooClassDef def, DiskAccessOneFile dao) {
		SchemaIndexEntry e = getSchema(def);
		if (e == null) {
			throw new JDOFatalDataStoreException(); 
		}

		dao.readObject(def);

		//def.associateSuperDef(defSuper);
		def.associateFields();
		//and check for indices
		//TODO maybe we do not need this for a refresh...
		for (ZooFieldDef f: def.getAllFields()) {
			if (e.getIndex(f) != null) {
				f.setIndexed(true);
				f.setUnique(e.isUnique(f));
			}
		}
	}

	
	/**
	 * @param node 
	 * @return List of all schemata in the database. These are loaded when the database is opened.
	 */
	public Collection<ZooClassDef> readSchemaAll(DiskAccessOneFile dao, Node node) {
		HashMap<Long, ZooClassDef> ret = new HashMap<Long, ZooClassDef>();
		for (SchemaIndexEntry se: schemaIndex.values()) {
		    for (long schemaOid: se.schemaOids) {
    			ZooClassDef def = (ZooClassDef) dao.readObject(schemaOid);
    			ret.put( def.getOid(), def );
    			se.classDef = def;
		    }
		}
		// assign versions
		for (ZooClassDef def: ret.values()) {
			def.associateVersions(ret);
		}
		
		// assign super classes
		for (ZooClassDef def: ret.values()) {
			if (def.getSuperOID() != 0) {
				def.associateSuperDef( ret.get(def.getSuperOID()) );
			}
		}
		
		//associate fields
		for (ZooClassDef def: ret.values()) {
			def.associateFields();
			//and check for indices
			SchemaIndexEntry se = getSchema(def);
			for (ZooFieldDef f: def.getAllFields()) {
				if (se.getIndex(f) != null) {
					f.setIndexed(true);
					f.setUnique(se.isUnique(f));
				}
			}
		}

		//build proxy structure
		for (ZooClassDef def: ret.values()) {
			if (def.getVersionProxy() == null) {
				ZooClassDef latest = def;
				while (latest.getNextVersion() != null) {
					latest = latest.getNextVersion();
				}
				//this associates proxies to super-classes and previous versions recursively
				latest.associateProxy( new ZooClassProxy(latest, node) );
			}
		}
		
		return ret.values();
	}

	public void defineSchema(ZooClassDef def) {
		String clsName = def.getClassName();
		
		//search schema in index
		for (SchemaIndexEntry e: schemaIndex.values()) {
			if (e.classDef.getClassName().equals(clsName)) {
	            throw new JDOFatalDataStoreException("Schema is already defined: " + clsName + 
	                    " oid=" + Util.oidToString(def.getOid()));
			}
		}
		
        // check if such an entry exists!
        if (getSchema(def) != null) {
            throw new JDOFatalDataStoreException("Schema is already defined: " + clsName + 
                    " oid=" + Util.oidToString(def.getOid()));
        }
        SchemaIndexEntry entry = new SchemaIndexEntry(file, def);
        schemaIndex.put(def.getSchemaId(), entry);
        markDirty();
	}

	public void undefineSchema(ZooClassDef sch) {
		//We remove it from known schema list.
		SchemaIndexEntry entry = schemaIndex.remove(sch.getSchemaId());
		markDirty();
		if (entry == null) {
			String cName = sch.getClassName();
			throw new JDOUserException("Schema not found: " + cName);
		}
		
		//field indices
		for (FieldIndex fi: entry.fieldIndices) {
			fi.index.clear();
		}
		
		//pos index
        for (PagedPosIndex oi: entry.objIndex) {
            oi.clear();
        }
		entry.objIndex = new PagedPosIndex[0];
	}	

	public void newSchemaVersion(ZooClassDef defOld, ZooClassDef defNew) {
		long oid = defNew.getOid();
		
		//At the moment we just add new version to the List. Is this sensible? I don't know.
		//TODO
		//It could make sense to scrap this list and use the schema-extent instead???
		
        // check if such an entry exists!
        if (getSchema(defNew) != null) {
            throw new JDOFatalDataStoreException("Schema is already defined: " + 
                    defNew.getClassName() + " oid=" + Util.oidToString(oid));
        }
        SchemaIndexEntry entry = schemaIndex.get(defNew.getSchemaId());
        entry.addVersion(defNew);
        markDirty();
	}

	public void deleteSchema(ZooClassDef sch) { 
		if (!sch.getVersionProxy().getSubProxies().isEmpty()) {
			//TODO first delete subclasses
			System.out.println("STUB delete subdata pages.");
		}
	}

	public ArrayList<Integer> debugPageIdsAttrIdx() {
	    ArrayList<Integer> ret = new ArrayList<Integer>();
        for (SchemaIndexEntry e: schemaIndex.values()) {
            for (FieldIndex fi: e.fieldIndices) {
                ret.addAll(fi.index.debugPageIds());
            }
        }
        return ret;
	}

	public void renameSchema(ZooClassDef def, String newName) {
		//We remove it from known schema list.
		SchemaIndexEntry entry = getSchema(def);
		markDirty();
		if (entry == null) {
			String cName = def.getClassName();
			throw new JDOUserException("Schema not found: " + cName);
		}

		//Nothing to do, just rewrite it here.
		//TODO remove this method, should be automatically rewritten if ClassDef is dirty. 
	}

	public void revert(int rootPage) {
		schemaIndex.clear();
		pageId = rootPage;
		readIndex();
	}

    public void refreshIterators() {
        for (SchemaIndexEntry e: schemaIndex.values()) {
            for (PagedPosIndex objInd: e.objIndex) {
                if (objInd != null) {
                    objInd.refreshIterators();
                }
            }
            for (FieldIndex fi: e.fieldIndices) {
                fi.index.refreshIterators();
            }
        }
    }
}
