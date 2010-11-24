package org.zoodb.jdo.internal.server;


import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.zoodb.jdo.internal.SerialInput;
import org.zoodb.jdo.internal.SerialOutput;
import org.zoodb.jdo.internal.server.index.PagedOidIndex;

/**
 * This class serves as a mediator between the serializer and the file access class. It has the
 * following features:
 * - automatically (de-)allocated required pages
 * - manage multiple small object on a single page including rewriting and resizing objects and
 *   turning single-objects pages into multi-object pages and vice versa
 * - for single-object pages, it manages seamless writing over pages boundaries 
 * 
 * @author Tilmann Z�schke
 *
 */
public class PagedObjectAccess implements SerialInput, SerialOutput {

	private final PageAccessFile _file;
	private int _currentPage = -1;
	private PAGE_TYPE _currentPageType = PAGE_TYPE.NOT_SET;
	private boolean _currentPageHasChanged = false;
	private boolean _isWriting = false;
	private PagedOidIndex _oidIndex;
	private int _currentOffs;
	private int _objBeginOffs;
	private long _currentOid;
	private int _currentObjCount;

	//TODO Tuning parameter
	//This can be used as tuning parameter:
	//- setting it to '0' may waste some space, but improves write performance 
	private int _freeSpacesMax = 10;
	private List<Integer> _freeSpaces = new LinkedList<Integer>();
	//TODO implement!
	
	private enum PAGE_TYPE {
		NOT_SET,
		MULTI_OBJ, //Page has no sliced object, but can have multiple objects
		SINGLE_OBJ;  //Page slices (large) objects, but can have only one object.
	}
	
	public PagedObjectAccess(PageAccessFile file, PagedOidIndex oidIndex) throws IOException {
		_file = file;
		_currentPage = -1;
		_oidIndex = oidIndex;
	}

	void startWriting(long oid) {
		if (_isWriting) {
			throw new IllegalStateException();
		}
		_isWriting = true;
		
		//TODO do not unlock, in stead make sure that we have the right lock!
		_file.unlock();
		
		_currentOid = oid;
		
		// first object in this session/page? 
		if (_currentPage == -1) {   // == NO_SET
			_currentPage = _file.allocateAndSeek(true);  //TODO reuse deleted pages from deleted-page index
			_currentPageType = PAGE_TYPE.NOT_SET;
			_currentOffs = 0;
			_currentObjCount = 0;
			_objBeginOffs = 0;
			return;
		}
		//writing to an existing page... must mean that there are already other objects here
		//assert type=MULTI_OBJ
		if (_currentPageType != PAGE_TYPE.MULTI_OBJ) {
			throw new IllegalStateException("Illegal page type: " + _currentPageType.name() + " #" + _currentPage);
		}
		//remember current position, in case we need to move obj to a different page.
		_objBeginOffs = _currentOffs;
		//TODO maybe we can avoid this...
		_file.assurePos(_currentPage, _currentOffs);
	
		//TODO set page type when finishing off objects
		
		//TODO remember we are not writing to the same pages ever, we are COW!
		
		//TODO if an object gets moved to a separate page, store remaining page space in a top-10
		// for reuse.
		//TODO do this only for multi-obj-pages
		//TODO only do this with pages that are either 
		//(> 50% free) || 32(?)byte free && >1 object on page). -> estimate average obj size...???
	}
	
	void stopWriting() {
		if (!_isWriting) {
			throw new IllegalStateException();
		}
		_isWriting = false;
		
		//No need to flush here. We may write more objects to the buffer. If not, then the next
		//seek() will perform a flush anyway.
		
		_oidIndex.addOid(_currentOid, _currentPage, _objBeginOffs);
		_currentObjCount ++;
		
		_currentOffs = _file.getOffset();
		
		//TODO is this a good idea?
		//close page if objects are likely to be too large
		// cOff/n * (n+1) >= PAGE_SIZE ---> cOff*(n+1) >= n*PAGE_SIZE
		if ( (_currentObjCount + 1)*(_currentOffs) 
				>= (_currentObjCount * DiskAccessOneFile.PAGE_SIZE) ) {
			_currentPage = -1;
		} else if (_currentPageType == PAGE_TYPE.SINGLE_OBJ) {
			_currentPage = -1;
			System.out.println("Eh??  SingleObjectPage????");
		} else {
			_currentPageType = PAGE_TYPE.MULTI_OBJ;
		}
		
	}	
	
	public String readString() throws IOException {
//		int len = _file.getInt(); //max 127
//		StringBuilder sb = new StringBuilder(len);
//		for (int i = 0; i < len; i++) {
//			char b = (char) _file.get();
//			sb.append(b);
//		}
//		return sb.toString();
		if (true) throw new RuntimeException();
		return _file.readString();
	}

	public void writeString(String string) throws IOException {
//		_file.putInt(string.length()); //max 127
//		for (int i = 0; i < string.length(); i++) {
//			_file.put((byte) string.charAt(i));
//		}
		_file.writeString(string);
	}

//	public int allocatePage() throws IOException {
//		int nPages = _lastPage.addAndGet(1);
////		System.out.println("Allocating page ID: " + nPages);
//		return nPages;
//	}
//
//	@Override
//	public void setLastAllocatedPage(int lastPage) {
//		_lastPage.set(lastPage);
//	}
//
//	@Override
//	public int getLastAllocatedPage() {
//		return _lastPage.get();
//	}

	public void close() {
		flush();
		_file.flush();
		_file.close();
	}

	/**
	 * Not a true flush, just writes the stuff...
	 */
	public void flush() {
		_file.flush();
		_currentPage = -1;
	}
	
//	public void writeData() {
//		try {
//			if (_currentPageHasChanged) {
//				_file.flip();
//				_file.write(_file, _currentPage * DiskAccessOneFile.PAGE_SIZE);
//				_currentPageHasChanged = false;
//			}
//		} catch (IOException e) {
//			throw new JDOFatalDataStoreException("Error writing page: " + _currentPage, e);
//		}
//	}
//	
//	/**
//	 * 
//	 * @param page
//	 * @throws IOException
//	 * @deprecated ?? remove later ?
//	 */
//	public final void checkOverflow(int page) throws IOException {
////		if (_raf.getFilePointer() >= (page+1) * DiskAccessOneFile.PAGE_SIZE) {
////			throw new IllegalStateException("Page overflow: " + 
////					(_raf.getFilePointer() - (page+1) * DiskAccessOneFile.PAGE_SIZE));
////		}
//		//TODO remove, the buffer will throw an exception any.
//		//-> keep it in case we use longer buffers???
//		if (_file.position() >= DiskAccessOneFile.PAGE_SIZE) {
//			throw new IllegalStateException("Page overflow: " + _file.position());
//		}
//	}

	@Override
	public boolean readBoolean() {
		if (true) throw new RuntimeException();
		return _file.readBoolean();
	}

	@Override
	public byte readByte() {
		if (true) throw new RuntimeException();
		return _file.readByte();
	}

	@Override
	public char readChar() {
		if (true) throw new RuntimeException();
		return _file.readChar();
	}

	@Override
	public double readDouble() {
		if (true) throw new RuntimeException();
		return _file.readDouble();
	}

	@Override
	public float readFloat() {
		return _file.readFloat();
	}

	@Override
	public void readFully(byte[] array) {
		if (true) throw new RuntimeException();
		_file.readFully(array);
	}

	@Override
	public int readInt() {
		if (true) throw new RuntimeException();
		return _file.readInt();
	}

	@Override
	public long readLong() {
		if (true) throw new RuntimeException();
		return _file.readLong();
	}

	@Override
	public short readShort() {
		if (true) throw new RuntimeException();
		return _file.readShort();
	}

	@Override
	public void write(byte[] array) {
		_currentPageHasChanged = true;
		_file.write(array);
	}

	@Override
	public void writeBoolean(boolean boolean1) {
		_currentPageHasChanged = true;
		//_file.writeBoolean((byte) (boolean1 ? 1 : 0));
		_file.writeBoolean(boolean1);
	}

	@Override
	public void writeByte(byte byte1) {
		_currentPageHasChanged = true;
		_file.writeByte(byte1);
	}

	@Override
	public void writeChar(char char1) {
		_currentPageHasChanged = true;
		_file.writeChar(char1);
	}

	@Override
	public void writeChars(String s) {
//		for (int i = 0; i < s.length(); i++) {
//			_currentPageHasChanged = true;
			_file.writeChars(s);//.charAt(i));
//		}
	}

	@Override
	public void writeDouble(double double1) {
		_currentPageHasChanged = true;
		_file.writeDouble(double1);
	}

	@Override
	public void writeFloat(float float1) {
		_currentPageHasChanged = true;
		_file.writeFloat(float1);
	}

	@Override
	public void writeInt(int int1) {
		_currentPageHasChanged = true;
		_file.writeInt(int1);
	}

	@Override
	public void writeLong(long long1) {
		_currentPageHasChanged = true;
//		System.out.println("W_POS=" + _file.position() + "/" + _fc.position());
		_file.writeLong(long1);
	}

	@Override
	public void writeShort(short short1) {
		_currentPageHasChanged = true;
		_file.writeShort(short1);
	}

	/**
	 * This can be necessary when subsequent objects are of a different class.
	 */
	public void newPage() {
		_currentPage = -1;
	}
	
	@Override
	public String toString() {
		return "ObjectWriter:: page=" + _currentPage + " ofs=" + _currentOffs + 
		" oid=" + _currentOid;
	}
}
