/*
 * Copyright 2009-2014 Tilmann Zaeschke. All rights reserved.
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
package org.zoodb.internal.server.index.btree;

import org.zoodb.internal.server.index.LongLongIndex;
import org.zoodb.internal.server.index.LongLongIndex.LLEntry;
import org.zoodb.internal.util.DBLogger;

import java.util.ConcurrentModificationException;
import java.util.LinkedList;
import java.util.NoSuchElementException;

public abstract class BTreeLeafEntryIterator implements
		LongLongIndex.LLEntryIterator {
	protected BTree tree;
	protected BTreeNode curLeaf;
	protected int curPos;
	protected LinkedList<BTreeNode> ancestors;
    protected LinkedList<Integer> positions;

    protected long start = Long.MIN_VALUE;
    protected long end = Long.MAX_VALUE;
    
    // used to throw errors when modifying the
    // tree while using the iterator
    private final int modCount;
    private final long txId; 

    abstract void updatePosition();
    abstract void setFirstLeaf();

	public BTreeLeafEntryIterator(BTree tree) {
		init(tree);
        this.modCount = tree.getModcount();
        this.txId = this.getTxId();
		setFirstLeaf();
	}

    public BTreeLeafEntryIterator(BTree tree, long start, long end) {
        init(tree);
        this.modCount = tree.getModcount();
        this.txId = this.getTxId();
        //ToDo get smallest key and value from tree
        this.start = start;
        this.end = end;
        setFirstLeaf();
    }

	@Override
	public boolean hasNext() {
        checkValidity();
		return  curLeaf != null && !tree.isEmpty() && tree.getRoot().getNumKeys() > 0;
	}

	@Override
	public LLEntry next() {
        checkValidity();
		if (curLeaf == null) {
			throw new NoSuchElementException();
		} else {
			LLEntry retEntry = new LLEntry(curLeaf.getKey(curPos),
					curLeaf.getValue(curPos));
            updatePosition();
			return retEntry;
		}
	}

	@Override
	public void close() {
		// TODO release clones if there are any
		curLeaf = null;
	}

	public void reset() {
		setFirstLeaf();
	}

	@Override
	public void remove() {
		throw new UnsupportedOperationException();
	}
	
	@Override
	public boolean hasNextULL() {
		return this.hasNext();
	}

	@Override
	public LongLongIndex.LLEntry nextULL() {
		return this.next();
	}

	@Override
	public long nextKey() {
		return this.next().getKey();
	}

    protected BTreeNode getLefmostLeaf(BTreeNode node) {
        if (node.isLeaf()) {
            return node;
        }
        BTreeNode current = node;
        while (!current.isLeaf()) {
            ancestors.push(current);
            positions.push(0);
            current = current.getChild(0);
        }
        return current;
    }

    protected BTreeNode getRightMostLeaf(BTreeNode node) {
        if(node.isLeaf()) {
            return node;
        }
        BTreeNode current = node;
        while (!current.isLeaf()) {
            ancestors.push(current);
            int numKeys = current.getNumKeys();
            positions.push(numKeys);
            current = current.getChild(numKeys);
        }
        return current;
    }
    
	public void checkValidity() {
		long storageTxId = getTxId();
		if (this.txId != storageTxId) {
            throw DBLogger.newUser("This iterator has been invalidated by commit() or rollback().");
		}
		if (this.modCount != tree.getModcount()) {
			throw new ConcurrentModificationException();
		}
	}
	
	public long getTxId() {
		// txId is only relevant when we are dealing with PagedBTrees on
		// StorageBufferManagers
		if (this.tree instanceof PagedBTree) {
			return ((PagedBTree)tree).getBufferManager().getTxId();
		}
		throw new UnsupportedOperationException(this.tree.getClass().getName());
	}

    private void init(BTree tree) {
        this.tree = tree;
        this.curLeaf = null;
        this.curPos = -1;
        this.ancestors = new LinkedList<>();
        this.positions = new LinkedList<>();
    }

    protected void populateAncestorStack(long key, long value) {
        BTreeNode current = tree.getRoot();
        int position;
        while (!current.isLeaf()) {
            position = current.findKeyValuePos(key, value);
            //position = position > 0 ? position - 1 : 0;
            positions.push(position);
            ancestors.push(current);
            current = current.getChild(position);
        }
        curLeaf = current;
        curPos = curLeaf.findKeyValuePos(key, value);
    }


}
