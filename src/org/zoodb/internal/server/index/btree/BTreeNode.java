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

import java.util.NoSuchElementException;

import org.zoodb.internal.server.index.btree.prefix.PrefixSharingHelper;

/**
 * Represents the node of a B+ tree.
 * 
 * @author Jonas Nick
 * @author Bogdan Vancea
 */
public abstract class BTreeNode {

	private boolean isLeaf;
	private boolean isRoot;
    protected int pageSize;
    protected int pageSizeThreshold;

    //It is very important always update this after modifying the keys/values/children
    protected int currentSize;

    protected long prefix;

	protected int numKeys;
	private long[] keys;

	private long[] values;
    protected int[] childSizes;

	protected int valueElementSize;

	public BTreeNode(int pageSize, boolean isLeaf, boolean isRoot, int valueElementSize) {
		this.isLeaf = isLeaf;
		this.isRoot = isRoot;
        this.pageSize = pageSize;
        this.pageSizeThreshold = (int) (pageSize * 0.75);
        this.valueElementSize = valueElementSize;

        initializeEntries();
	}

    public abstract long getNonKeyEntrySizeInBytes(int numKeys);

    public abstract void initializeEntries();
    protected abstract void initChildren(int size);

    public abstract boolean equalChildren(BTreeNode other);
    public abstract void copyChildren(BTreeNode source, int sourceIndex,
                                      BTreeNode dest, int destIndex, int size);
    protected abstract BTreeNode leftSiblingOf(BTreeNode node);
    protected abstract BTreeNode rightSiblingOf(BTreeNode node);
    public abstract BTreeNode getChild(int index);
    public abstract void setChild(int index, BTreeNode child);
    public abstract BTreeNode[] getChildNodes();
    public abstract void setChildren(BTreeNode[] children);
    public abstract void markChanged();
    // closes (destroys) node
    public abstract void close();
    /*
        Node modification operations
     */
    public abstract void migrateEntry(int destinationPos, BTreeNode source, int sourcePos);
    public abstract void setEntry(int pos, long key, long value);

    public abstract void copyFromNodeToNode(int srcStartK, int srcStartC, BTreeNode destination, int destStartK, int destStartC, int keys, int children);
    public abstract void shiftRecords(int startIndex, int endIndex, int amount);
    public abstract void shiftRecordsRight(int amount);
    public abstract void shiftRecordsLeftWithIndex(int startIndex, int amount);
    protected abstract boolean smallerThanKeyValue(int position, long key, long value);
    protected abstract boolean allowNonUniqueKeys();
    public abstract int computeMaxPossibleEntries();
    public abstract int computeSize();
    public abstract int storageHeaderSize();
    public abstract boolean fitsIntoOneNodeWith(BTreeNode neighbour);

    public abstract int binarySearch(long key, long value);
    

    /**
     * Insert a key/value pair into the node. The node must be guaranteed not to overflow
     * after the key/value pair received as an argument is inserted
     * @param key
     * @param value
     */
    public boolean put(long key, long value, boolean onlyIfNotSet) {
        if (!isLeaf()) {
            throw new IllegalStateException("Should only be called on leaf nodes.");
        }

        int pos;
        if (getNumKeys() == 0) {
        	pos = 0;
            increaseNumKeys(1);
        } else {
        	pos = binarySearch(key, value);
	        if (onlyIfNotSet && pos >= 0) {
	        	return false;
	        }
	        if (pos >= 0 && getKey(pos) == key && (!allowNonUniqueKeys() || getValue(pos) == value)) {
	        	//
	        } else {
	        	pos = -(pos + 1);
	            if (!smallerThanKeyValue(pos, key, value)) {
	            	pos++;
	            }
	            shiftRecords(pos, pos + 1, getNumKeys() - pos);
	            increaseNumKeys(1);
	        }
        }
        setKey(pos, key);
        setValue(pos, value);

        //signal change
        markChanged();
        recomputeSize();
        return true;
    }

    public BTreeNode findChild(long key, long value) {
        return getChild(findKeyValuePos(key, value));
    }

    /**
     * Checks if the tree contains the key/value pair received as an argument.
     * @param key
     * @param value
     * @return
     */
    public boolean containsKeyValue(long key, long value) {
        int position = binarySearch(key, value);
        return position >= 0;
    }

    public int findKeyValuePos(long key, long value) {
        if (getNumKeys() == 0) {
            return 0;
        }
        int closest = binarySearch(key, value);

        // if the key is not here, find the child subtree that has it
        if (closest < 0) {
            closest = -(closest + 1);
            if (smallerThanKeyValue(closest, key, value)) {
                return closest;
            }
        }
        return closest + 1;
    }
    
    /**
     * Inner-node put. Places key to the left of the next bigger key k'.
     *
     * Requires that key <= keys(newUniqueNode) all elements of the left child of k'
     * are smaller than key node is not full. Assumes that leftOf(key') <=
     * keys(newUniqueNode)
     *
     * @param key
     * @param newNode
     */
    public void put(long key, long value, BTreeNode newNode) {
    	//TODO remove? This is a pure test method...
        if (isLeaf()) {
            throw new IllegalStateException(
                    "Should only be called on inner nodes.");
        } else if (getNumKeys() == 0) {
            throw new IllegalStateException(
                    "Should only be called when node is non-empty.");
        }
        int pos = findKeyValuePos(key, value);
        put(key, value, pos, newNode);
    }

    public void put(long key, long value, int pos, BTreeNode newNode) {
        if (pos > 0 && (getKey(pos - 1) == key && getValue(pos - 1) == value)) {
            throw new IllegalStateException(
                    "Tree is not allowed to have non-unique values.");
        }
        int recordsToMove = getNumKeys() - pos;
        shiftChildren(pos + 1, pos + 2, recordsToMove);
        setChild(pos + 1, newNode);

        shiftKeys(pos, pos + 1, recordsToMove);
        if (values != null) {
            shiftValues(pos, pos + 1, recordsToMove);
        }
        setEntry(pos, key, value);
        increaseNumKeys(1);

        recomputeSize();
    }

    /**
     * Root-node put.
     *
     * Used when a non-leaf root is empty and will be populated by a single key
     * and two nodes.
     *
     * @param key
     *            The new key on the root.
     * @param left
     *            The left node.
     * @param right
     *            The right node.
     */
    public void put(long key, long value,  BTreeNode left, BTreeNode right) {
        if (!isRoot()) {
            throw new IllegalStateException(
                    "Should only be called on the root node.");
        }
        setEntry(0, key, value);
        setNumKeys(1);

        setChild(0, left);
        setChild(1, right);

        recomputeSize();
    }

    /**
     * Delete the key from the node.
     *
     * @param key
     */
    public long delete(long key, long value) {
        if (!isLeaf()) {
            throw new IllegalStateException("Should be a leaf node");
        }
        final int keyPos = findKeyValuePos(key, value);
        if (keyPos == 0) {
            throw new NoSuchElementException("key not found: " + key + " / " + value);
        }
        int recordsToMove = getNumKeys() - keyPos;
        long oldValue = getValue(keyPos - 1);
        shiftRecords(keyPos, keyPos - 1, recordsToMove);
        decreaseNumKeys(1);

        return oldValue;
    }

    public int computeIndexForSplit(boolean isUnique) {
        int weightKey = (this.isLeaf() || (isUnique)) ? this.getValueElementSize() : 0;
        int weightChild = (isLeaf() ? 0 : 4);
        int header = storageHeaderSize();
        int keysInLeftNode = PrefixSharingHelper.computeIndexForSplitAfterInsert(
                getKeys(), getNumKeys(),
                header, weightKey, weightChild, getPageSize());
        return keysInLeftNode;
    }

    protected void shiftRecordsLeft(int amount) {
        markChanged();
        shiftRecordsLeftWithIndex(0, amount);
    }

    public void shiftKeys(int startIndex, int endIndex, int amount) {
        markChanged();
        System.arraycopy(getKeys(), startIndex, getKeys(), endIndex, amount);
    }

    protected void shiftValues(int startIndex, int endIndex, int amount) {
        markChanged();
        System.arraycopy(getValues(), startIndex, getValues(), endIndex, amount);
    }

    public void shiftChildren(int startIndex, int endIndex, int amount) {
        markChanged();
        copyChildren(this, startIndex, this, endIndex, amount);
    }

    public BTreeNode rightSibling(BTreeNode parent) {
        return (parent == null) ? null : parent.rightSiblingOf(this);
    }

    public BTreeNode rightSibling(int childIndex) {
        int rightIndex = childIndex + 1;
        if (rightIndex > numKeys) {
            return null;
        } else {
            return getChild(rightIndex);
        }
    }

    public BTreeNode leftSibling(int childIndex) {
        int leftIndex = childIndex - 1;
        if (leftIndex < 0) {
            return null;
        } else {
            return getChild(leftIndex);
        }
    }

    public boolean leftSiblingNotFull(int childIndex) {
        int leftIndex = childIndex - 1;
        if (childSizes == null || leftIndex < 0) {
            return false;
        }
        return notFullTest(childSizes[leftIndex]);
    }

    public void setKey(int index, long key) {
        getKeys()[index] = key;

        //signal change
        markChanged();
    }

    public void setValue(int index, long value) {
        getValues()[index] = value;

        //signal change
        markChanged();
    }

    public boolean isUnderFull() {
        if (isRoot()) {
            return getNumKeys() == 0;
        }
        return underfullTest(currentSize);
    }

    private boolean notFullTest(int size) {
        return size < getPageSize();
    }

    private boolean underfullTest(int size) {
        return size < pageSizeThreshold;
    }

    private boolean extraKeysTest(int size) {
        return size > pageSizeThreshold;
    }

    public boolean hasExtraKeys() {
        if (isRoot()) {
            return true;
        }
        return getNumKeys() > 2 && extraKeysTest(getCurrentSize());
    }

    public boolean isFull() {
        return getCurrentSize() == pageSize;
    }

    public boolean overflows() {
        return getCurrentSize() > pageSize;
    }

    public void increaseNumKeys(int amount) {
        int newNumKeys = getNumKeys() + amount;
        setNumKeys(newNumKeys);
    }

    public void decreaseNumKeys(int amount) {
        int newNumKeys = getNumKeys() - amount;
        setNumKeys(newNumKeys);
    }

    protected void initKeys(int size) {
        setKeys(new long[size]);
        setNumKeys(0);
    }

    protected void initValues(int size) {
        setValues(new long[size]);
    }

    public long getValue(int index) {
        return (values == null) ? - 1 : values[index];
    }

    public long getKey(int index) {
        return keys[index];
    }

    public boolean isLeaf() {
        return isLeaf;
    }

    public boolean isRoot() {
        return isRoot;
    }

    public long getSmallestKey() {
        return keys[0];
    }

    public long getLargestKey() {
        return keys[numKeys - 1];
    }

    public long getSmallestValue() {
        return (values != null) ? values[0] : -1;
    }

    public long getLargestValue() {
        return (values != null) ? values[numKeys -1] : -1;
    }

    public int getNumKeys() {
        return numKeys;
    }

    public long[] getKeys() {
        return keys;
    }

    public long[] getValues() {
        return values;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setNumKeys(int newNumKeys) {
        if (newNumKeys < 0 || newNumKeys > getKeys().length) {
        	throw new IllegalStateException();
        }
        markChanged();
        this.numKeys = newNumKeys;
    }

    public void setKeys(long[] keys) {
        markChanged();
        this.keys = keys;
    }

    public void setValues(long[] values) {
        markChanged();
        this.values = values;
    }

	public void setIsRoot(boolean isRoot) {
		this.isRoot = isRoot;
	}

    public String toString() {
        String ret = (isLeaf() ? "leaf" : "inner") + "-node: k:";
        ret += "[";
        for (int i = 0; i < this.getNumKeys(); i++) {
            ret += Long.toString(keys[i]);
            if (i != this.getNumKeys() - 1)
                ret += " ";
        }
        ret += "]";
        ret += ",   \tv:";
        ret += "[";
        for (int i = 0; i < this.getNumKeys(); i++) {
            ret += Long.toString(values[i]);
            if (i != this.getNumKeys() - 1)
                ret += " ";
        }
        ret += "]";

        if (!isLeaf()) {
            ret += "\n\tc:";
            if (this.getNumKeys() != 0) {
                for (int i = 0; i < this.getNumKeys() + 1; i++) {
                    String[] lines = this.getChild(i).toString()
                            .split("\r\n|\r|\n");
                    for (String l : lines) {
                        ret += "\n\t" + l;
                    }
                }
            }
        }
        return ret;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof BTreeNode))
            return false;

        BTreeNode bTreeNode = (BTreeNode) o;

        if (isLeaf() != bTreeNode.isLeaf())
            return false;
        if (getNumKeys() != bTreeNode.getNumKeys())
            return false;
        if (pageSize != bTreeNode.pageSize)
            return false;
        if (!isLeaf() && !equalChildren(bTreeNode))
            return false;
        if (!arrayEquals(getKeys(), bTreeNode.getKeys(), getNumKeys()))
            return false;
        // checking for parent equality would result in infinite loop
        if (!arrayEquals(getValues(), bTreeNode.getValues(), getNumKeys()))
            return false;

        return true;
    }

    protected <T> boolean arrayEquals(T[] first, T[] second, int size) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        if (first.length < size || second.length < size) {
            return false;
        }

        for (int i = 0; i < size; i++) {
            if ((first[i] != second[i]) && (first[i] != null)
                    && (!first[i].equals(second[i]))) {
                return false;
            }
        }
        return true;
    }

    private boolean arrayEquals(long[] first, long[] second, int size) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null) {
            return false;
        }
        if (first.length < size || second.length < size) {
            return false;
        }

        for (int i = 0; i < size; i++) {
            if (!(first[i] == second[i])) {
                return false;
            }
        }
        return true;
    }

    public long getNonKeyEntrySizeInBytes() {
        return getNonKeyEntrySizeInBytes(getNumKeys());
    }

    public int getKeyArraySizeInBytes() {
        if (getNumKeys() == 0) {
            return 0;
        }
        return PrefixSharingHelper.encodedArraySize(getNumKeys(), prefix);
    }

    public void recomputeSize() {
        recomputePrefix();
        this.currentSize = computeSize();
    }

    public int getCurrentSize() {
        return currentSize;
    }

    public void recomputePrefix() {
        this.prefix = (getNumKeys() == 0) ? 0 : PrefixSharingHelper.computePrefix(getSmallestKey(), getLargestKey());
    }

	public long getPrefix() {
		return prefix;
	}
	
    public int getValueElementSize() {
    	return this.valueElementSize;
    }

    public long getChildSize(int childIndex) {
        return childSizes[childIndex];
    }

    public void setChildSize(int size, int childIndex) {
        if (this.childSizes != null) {
            this.childSizes[childIndex] = size;
        }
    }

    public int[] getChildSizes() {
        return childSizes;
    }
    
}
