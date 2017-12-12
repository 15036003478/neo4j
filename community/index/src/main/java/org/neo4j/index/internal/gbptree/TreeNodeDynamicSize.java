/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.index.internal.gbptree;

import java.util.StringJoiner;

import org.neo4j.collection.primitive.PrimitiveIntStack;
import org.neo4j.io.pagecache.PageCursor;

import static java.lang.String.format;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.BYTE_SIZE_KEY_SIZE;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.BYTE_SIZE_OFFSET;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.BYTE_SIZE_TOTAL_OVERHEAD;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.BYTE_SIZE_VALUE_SIZE;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.hasTombstone;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.putKeyOffset;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.putKeySize;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.putTombstone;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.putValueSize;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.readKeyOffset;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.readKeySize;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.readValueSize;
import static org.neo4j.index.internal.gbptree.DynamicSizeUtil.stripTombstone;
import static org.neo4j.index.internal.gbptree.GenerationSafePointerPair.read;
import static org.neo4j.index.internal.gbptree.TreeNode.Type.INTERNAL;
import static org.neo4j.index.internal.gbptree.TreeNode.Type.LEAF;

public class TreeNodeDynamicSize<KEY, VALUE> extends TreeNode<KEY,VALUE>
{
    private static final int BYTE_POS_ALLOCOFFSET = BASE_HEADER_LENGTH;
    private static final int BYTE_POS_DEADSPACE = BYTE_POS_ALLOCOFFSET + bytesPageOffset();
    private static final int HEADER_LENGTH_DYNAMIC = BYTE_POS_DEADSPACE + bytesPageOffset();

    private static final int LEAST_NUMBER_OF_ENTRIES_PER_PAGE = 2;
    private static final int MINIMUM_ENTRY_SIZE_CAP = Long.SIZE;
    private final int keyValueSizeCap;

    TreeNodeDynamicSize( int pageSize, Layout<KEY,VALUE> layout )
    {
        super( pageSize, layout );

        keyValueSizeCap = totalSpace( pageSize ) / LEAST_NUMBER_OF_ENTRIES_PER_PAGE - BYTE_SIZE_TOTAL_OVERHEAD;

        if ( keyValueSizeCap < MINIMUM_ENTRY_SIZE_CAP )
        {
            throw new MetadataMismatchException(
                    "We need to fit at least %d key-value entries per page in leaves. To do that a key-value entry can be at most %dB " +
                            "with current page size of %dB. We require this cap to be at least %dB.",
                    LEAST_NUMBER_OF_ENTRIES_PER_PAGE, keyValueSizeCap, pageSize, Long.SIZE );
        }
    }

    @Override
    void writeAdditionalHeader( PageCursor cursor )
    {
        setAllocOffset( cursor, pageSize );
    }

    @Override
    KEY keyAt( PageCursor cursor, KEY into, int pos, Type type )
    {
        placeCursorAtActualKey( cursor, pos, type );

        // Read key
        int keySize = readKeySize( cursor );
        if ( keySize > keyValueSizeCap || keySize < 0 )
        {
            cursor.setCursorException( format( "Read unreliable key, keySize=%d, keyValueSizeCap=%d, keyHasTombstone=%b",
                    keySize, keyValueSizeCap, hasTombstone( keySize ) ) );
        }
        if ( type == LEAF )
        {
            progressCursor( cursor, bytesValueSize() );
        }
        layout.readKey( cursor, into, keySize );
        return into;
    }

    @Override
    void insertKeyAndRightChildAt( PageCursor cursor, KEY key, long child, int pos, int keyCount, long stableGeneration,
            long unstableGeneration )
    {
        // Where to write key?
        int currentKeyOffset = getAllocOffset( cursor );
        int keySize = layout.keySize( key );
        int newKeyOffset = currentKeyOffset - bytesKeySize() - keySize;

        // Write key
        cursor.setOffset( newKeyOffset );
        putKeySize( cursor, keySize );
        layout.writeKey( cursor, key );

        // Update alloc space
        setAllocOffset( cursor, newKeyOffset );

        // Write to offset array
        insertSlotsAt( cursor, pos, 1, keyCount, keyPosOffsetInternal( 0 ), keyChildSize() );
        cursor.setOffset( keyPosOffsetInternal( pos ) );
        putKeyOffset( cursor, newKeyOffset );
        writeChild( cursor, child, stableGeneration, unstableGeneration );
    }

    @Override
    void insertKeyValueAt( PageCursor cursor, KEY key, VALUE value, int pos, int keyCount )
    {
        // Where to write key?
        int currentKeyValueOffset = getAllocOffset( cursor );
        int keySize = layout.keySize( key );
        int valueSize = layout.valueSize( value );
        int newKeyValueOffset = currentKeyValueOffset - bytesKeySize() - bytesValueSize() - keySize - valueSize;

        // Write key and value
        cursor.setOffset( newKeyValueOffset );
        putKeySize( cursor, keySize );
        putValueSize( cursor, valueSize );
        layout.writeKey( cursor, key );
        layout.writeValue( cursor, value );

        // Update alloc space
        setAllocOffset( cursor, newKeyValueOffset );

        // Write to offset array
        insertSlotsAt( cursor, pos, 1, keyCount, keyPosOffsetLeaf( 0 ), bytesKeyOffset() );
        cursor.setOffset( keyPosOffsetLeaf( pos ) );
        putKeyOffset( cursor, newKeyValueOffset );
    }

    @Override
    void removeKeyValueAt( PageCursor cursor, int pos, int keyCount )
    {
        // Kill actual key
        placeCursorAtActualKey( cursor, pos, LEAF );
        int keyOffset = cursor.getOffset();
        int keySize = readKeySize( cursor );
        int valueSize = readValueSize( cursor );
        cursor.setOffset( keyOffset );
        putTombstone( cursor );

        // Update dead space
        int deadSpace = getDeadSpace( cursor );
        setDeadSpace( cursor, deadSpace + keySize + valueSize + bytesKeySize() + bytesValueSize() );

        // Remove from offset array
        removeSlotAt( cursor, pos, keyCount, keyPosOffsetLeaf( 0 ), bytesKeyOffset() );
    }

    @Override
    void removeKeyAndRightChildAt( PageCursor cursor, int keyPos, int keyCount )
    {
        // Kill actual key
        placeCursorAtActualKey( cursor, keyPos, INTERNAL );
        putTombstone( cursor );

        // Remove for offsetArray
        removeSlotAt( cursor, keyPos, keyCount, keyPosOffsetInternal( 0 ), keyChildSize() );
    }

    @Override
    void removeKeyAndLeftChildAt( PageCursor cursor, int keyPos, int keyCount )
    {
        // Kill actual key
        placeCursorAtActualKey( cursor, keyPos, INTERNAL );
        putTombstone( cursor );

        // Remove for offsetArray
        removeSlotAt( cursor, keyPos, keyCount, keyPosOffsetInternal( 0 ) - childSize(), keyChildSize() );

        // Move last child
        cursor.copyTo( childOffset( keyCount ), cursor, childOffset( keyCount - 1 ), childSize() );
    }

    @Override
    void setKeyAt( PageCursor cursor, KEY key, int pos, Type type )
    {
        throw new UnsupportedOperationException( "Implement me" );
    }

    @Override
    VALUE valueAt( PageCursor cursor, VALUE into, int pos )
    {
        placeCursorAtActualKey( cursor, pos, LEAF );

        // Read value
        int keySize = readKeySize( cursor );
        int valueSize = readValueSize( cursor );
        if ( valueSize > keyValueSizeCap )
        {
            cursor.setCursorException( format( "Read unreliable key, value size greater than cap: keySize=%d, keyValueSizeCap=%d",
                    valueSize, keyValueSizeCap ) );
        }
        progressCursor( cursor, keySize );
        layout.readValue( cursor, into, valueSize );
        return into;
    }

    @Override
    boolean setValueAt( PageCursor cursor, VALUE value, int pos )
    {
        placeCursorAtActualKey( cursor, pos, LEAF );

        int keySize = DynamicSizeUtil.readKeyOffset( cursor );
        int oldValueSize = DynamicSizeUtil.readValueSize( cursor );
        int newValueSize = layout.valueSize( value );
        if ( oldValueSize == newValueSize )
        {
            // Fine we can just overwrite
            progressCursor( cursor, keySize );
            layout.writeValue( cursor, value );
            return true;
        }
        return false;
    }

    private void progressCursor( PageCursor cursor, int delta )
    {
        cursor.setOffset( cursor.getOffset() + delta );
    }

    @Override
    long childAt( PageCursor cursor, int pos, long stableGeneration, long unstableGeneration )
    {
        cursor.setOffset( childOffset( pos ) );
        return read( cursor, stableGeneration, unstableGeneration, pos );
    }

    @Override
    void setChildAt( PageCursor cursor, long child, int pos, long stableGeneration, long unstableGeneration )
    {
        cursor.setOffset( childOffset( pos ) );
        writeChild( cursor, child, stableGeneration, unstableGeneration );
    }

    @Override
    int leafMaxKeyCount()
    {
        throw new UnsupportedOperationException( "Implement me" );
    }

    @Override
    boolean reasonableKeyCount( int keyCount )
    {
        return keyCount >= 0 && keyCount <= totalSpace( pageSize ) / BYTE_SIZE_TOTAL_OVERHEAD;
    }

    @Override
    boolean reasonableChildCount( int childCount )
    {
        throw new UnsupportedOperationException( "Implement me" );
    }

    @Override
    int childOffset( int pos )
    {
        // Child pointer to the left of key at pos
        return keyPosOffsetInternal( pos ) - childSize();
    }

    @Override
    boolean internalOverflow( PageCursor cursor, int currentKeyCount, KEY newKey )
    {
        // How much space do we have?
        int allocSpace = getAllocSpace( cursor, currentKeyCount, INTERNAL );
        int neededSpace = totalSpaceOfKeyChild( newKey );

        return neededSpace > allocSpace;
    }

    @Override
    Overflow leafOverflow( PageCursor cursor, int currentKeyCount, KEY newKey, VALUE newValue )
    {
        // How much space do we have?
        int deadSpace = getDeadSpace( cursor );
        int allocSpace = getAllocSpace( cursor, currentKeyCount, LEAF );

        // How much space do we need?
        int keySize = layout.keySize( newKey );
        int valueSize = layout.valueSize( newValue );
        int totalOverhead = bytesKeyOffset() + bytesKeySize() + bytesValueSize();
        int neededSpace = keySize + valueSize + totalOverhead;

        // There is your answer!
        return neededSpace < allocSpace ? Overflow.NO :
               neededSpace < allocSpace + deadSpace ? Overflow.NEED_DEFRAG : Overflow.YES;
    }

    @Override
    void defragmentLeaf( PageCursor cursor )
    {
        // Mark all offsets
        PrimitiveIntStack deadKeysOffset = new PrimitiveIntStack();
        PrimitiveIntStack aliveKeysOffset = new PrimitiveIntStack();
        recordDeadAndAlive( cursor, deadKeysOffset, aliveKeysOffset );

        /*
        BEFORE MOVE
                          v       aliveRangeOffset
        [X][_][_][X][_][X][_][_]
                   ^   ^          deadRangeOffset
                   |_____________ moveRangeOffset

        AFTER MOVE
                       v          aliveRangeOffset
        [X][_][_][X][X][_][_][_]
                 ^                 deadRangeOffset

         */
        int maxKeyCount = pageSize / (bytesKeySize() + bytesKeyOffset() + bytesValueSize());
        int[] oldOffset = new int[maxKeyCount];
        int[] newOffset = new int[maxKeyCount];
        int oldOffsetCursor = 0;
        int newOffsetCursor = 0;
        int aliveRangeOffset = pageSize; // Everything after this point is alive
        int deadRangeOffset; // Everything between this point and aliveRangeOffset is dead space

        // Rightmost alive keys does not need to move
        while ( deadKeysOffset.peek() < aliveKeysOffset.peek() )
        {
            aliveRangeOffset = aliveKeysOffset.poll();
        }

        do
        {
            // Locate next range of dead keys
            deadRangeOffset = aliveRangeOffset;
            while ( aliveKeysOffset.peek() < deadKeysOffset.peek() )
            {
                deadRangeOffset = deadKeysOffset.poll();
            }

            // Locate next range of alive keys
            int moveOffset = deadRangeOffset;
            while ( deadKeysOffset.peek() < aliveKeysOffset.peek() )
            {
                int moveKey = aliveKeysOffset.poll();
                oldOffset[oldOffsetCursor++] = moveKey;
                moveOffset = moveKey;
            }

            // Update offset mapping
            int deadRangeSize = aliveRangeOffset - deadRangeOffset;
            while ( oldOffsetCursor > newOffsetCursor )
            {
                newOffset[newOffsetCursor] = oldOffset[newOffsetCursor] + deadRangeSize;
                newOffsetCursor++;
            }

            // Do move
            while ( moveOffset < (deadRangeOffset - deadRangeSize) )
            {
                // Move one block
                deadRangeOffset -= deadRangeSize;
                aliveRangeOffset -= deadRangeSize;
                cursor.copyTo( deadRangeOffset, cursor, aliveRangeOffset, deadRangeSize );
            }
            // Move the last piece
            int lastBlockSize = deadRangeOffset - moveOffset;
            deadRangeOffset -= lastBlockSize;
            aliveRangeOffset -= lastBlockSize;
            cursor.copyTo( deadRangeOffset, cursor, aliveRangeOffset, lastBlockSize );
        }
        while ( !aliveKeysOffset.isEmpty() );
        // Update allocOffset
        setAllocOffset( cursor, aliveRangeOffset );

        // Update offset array
        int keyCount = keyCount( cursor );
        keyPos:
        for ( int pos = 0; pos < keyCount; pos++ )
        {
            int keyPosOffset = keyPosOffsetLeaf( pos );
            cursor.setOffset( keyPosOffset );
            int keyOffset = readKeyOffset( cursor );
            for ( int index = 0; index < oldOffsetCursor; index++ )
            {
                if ( keyOffset == oldOffset[index] )
                {
                    // Overwrite with new offset
                    cursor.setOffset( keyPosOffset );
                    putKeyOffset( cursor, newOffset[index] );
                    continue keyPos;
                }
            }
        }

        // Update dead space
        setDeadSpace( cursor, 0 );
    }

    @Override
    boolean leafUnderflow( PageCursor cursor, int keyCount )
    {
        int halfSpace = halfSpace();
        int allocSpace = getAllocSpace( cursor, keyCount, LEAF );
        int deadSpace = getDeadSpace( cursor );
        int availableSpace = allocSpace + deadSpace;

        return availableSpace > halfSpace;
    }

    @Override
    boolean canRebalanceLeaves( int leftKeyCount, int rightKeyCount )
    {
        throw new UnsupportedOperationException( "Implement me" );
    }

    @Override
    boolean canMergeLeaves( int leftKeyCount, int rightKeyCount )
    {
        throw new UnsupportedOperationException( "Implement me" );
    }

    @Override
    void doSplitLeaf( PageCursor leftCursor, int leftKeyCount, PageCursor rightCursor, int insertPos, KEY newKey,
            VALUE newValue, StructurePropagation<KEY> structurePropagation )
    {
        // Find middle
        int middlePos = middle( leftCursor, insertPos, newKey, newValue );
        int keyCountAfterInsert = leftKeyCount + 1;

        if ( middlePos == insertPos )
        {
            layout.copyKey( newKey, structurePropagation.rightKey );
        }
        else
        {
            keyAt( leftCursor, structurePropagation.rightKey, insertPos < middlePos ? middlePos - 1 : middlePos, LEAF );
        }
        int rightKeyCount = keyCountAfterInsert - middlePos;

        if ( insertPos < middlePos )
        {
            //                  v-------v       copy
            // before _,_,_,_,_,_,_,_,_,_
            // insert _,_,_,X,_,_,_,_,_,_,_
            // middle           ^
            moveKeysAndValues( leftCursor, middlePos - 1, rightCursor, 0, rightKeyCount );
            defragmentLeaf( leftCursor );
            insertKeyValueAt( leftCursor, newKey, newValue, insertPos, middlePos - 1 );
        }
        else
        {
            //                  v---v           first copy
            //                        v-v       second copy
            // before _,_,_,_,_,_,_,_,_,_
            // insert _,_,_,_,_,_,_,_,X,_,_
            // middle           ^

            // Copy everything in one go
            int newInsertPos = insertPos - middlePos;
            int keysToMove = leftKeyCount - middlePos;
            moveKeysAndValues( leftCursor, middlePos, rightCursor, 0, keysToMove );
            defragmentLeaf( leftCursor );
            insertKeyValueAt( rightCursor, newKey, newValue, newInsertPos, keysToMove );
        }
        TreeNode.setKeyCount( leftCursor, middlePos );
        TreeNode.setKeyCount( rightCursor, rightKeyCount );
    }

    private int getAllocSpace( PageCursor cursor, int keyCount, Type type )
    {
        int allocOffset = getAllocOffset( cursor );
        int endOfOffsetArray = type == LEAF ? keyPosOffsetLeaf( keyCount ) : keyPosOffsetInternal( keyCount );
        return allocOffset - endOfOffsetArray;
    }

    private void recordDeadAndAlive( PageCursor cursor, PrimitiveIntStack deadKeysOffset, PrimitiveIntStack aliveKeysOffset )
    {
        int currentOffset = getAllocOffset( cursor );
        while ( currentOffset < pageSize )
        {
            cursor.setOffset( currentOffset );
            int keySize = readKeySize( cursor );
            int valueSize = readValueSize( cursor );
            boolean dead = hasTombstone( keySize );
            keySize = stripTombstone( keySize );

            if ( dead )
            {
                deadKeysOffset.push( currentOffset );
            }
            else
            {
                aliveKeysOffset.push( currentOffset );
            }
            currentOffset += keySize + valueSize + bytesKeySize() + bytesValueSize();
        }
    }

    private void moveKeysAndValues( PageCursor fromCursor, int fromPos, PageCursor toCursor, int toPos, int count )
    {
        int rightAllocOffset = getAllocOffset( toCursor );
        for ( int i = 0; i < count; i++, toPos++ )
        {
            rightAllocOffset = transferRawKeyValue( fromCursor, fromPos + i, toCursor, rightAllocOffset );
            toCursor.setOffset( keyPosOffsetLeaf( toPos ) );
            putKeyOffset( toCursor, rightAllocOffset );
        }
        setAllocOffset( toCursor, rightAllocOffset );
    }

    /**
     * Transfer key and value from logical position in 'from' to physical position next to current alloc offset in 'to'.
     * Mark transferred key as dead.
     * @return new alloc offset in 'to'
     */
    private int transferRawKeyValue( PageCursor fromCursor, int fromPos, PageCursor toCursor, int rightAllocOffset )
    {
        // What to copy?
        placeCursorAtActualKey( fromCursor, fromPos, LEAF );
        int fromKeyOffset = fromCursor.getOffset();
        int keySize = readKeySize( fromCursor );
        int valueSize = readValueSize( fromCursor );

        // Copy
        int toCopy = bytesKeySize() + bytesValueSize() + keySize + valueSize;
        int newRightAllocSpace = rightAllocOffset - toCopy;
        fromCursor.copyTo( fromKeyOffset, toCursor, newRightAllocSpace, toCopy );

        // Put tombstone
        fromCursor.setOffset( fromKeyOffset );
        putTombstone( fromCursor );
        return newRightAllocSpace;
    }

    private int middle( PageCursor leftCursor, int insertPos, KEY newKey, VALUE newValue )
    {
        int halfSpace = halfSpace();
        int middle = 0;
        int currentPos = 0;
        int middleSpace = 0;
        int currentDelta = halfSpace;
        int prevDelta;
        boolean includedNew = false;

        do
        {
            // We may come closer to split by keeping one more in left
            middle++;
            currentPos++;
            int space;
            if ( currentPos == insertPos & !includedNew )
            {
                space = totalSpaceOfKeyValue( newKey, newValue );
                includedNew = true;
                currentPos--;
            }
            else
            {
                space = totalSpaceOfKeyValue( leftCursor, currentPos );
            }
            middleSpace += space;
            prevDelta = currentDelta;
            currentDelta = Math.abs( middleSpace - halfSpace );
        }
        while ( currentDelta < prevDelta );
        middle--; // Step back to the pos that most equally divide the available space in two
        return middle;
    }

    private int totalSpace( int pageSize )
    {
        return pageSize - HEADER_LENGTH_DYNAMIC;
    }

    private int halfSpace()
    {
        return totalSpace( pageSize ) / 2;
    }

    private int totalSpaceOfKeyValue( KEY key, VALUE value )
    {
        return bytesKeyOffset() + bytesKeySize() + bytesValueSize() + layout.keySize( key ) + layout.valueSize( value );
    }

    private int totalSpaceOfKeyChild( KEY key )
    {
        return bytesKeyOffset() + bytesKeySize() + childSize() + layout.keySize( key );
    }

    private int totalSpaceOfKeyValue( PageCursor cursor, int pos )
    {
        placeCursorAtActualKey( cursor, pos, LEAF );
        int keySize = readKeySize( cursor );
        int valueSize = readValueSize( cursor );
        return bytesKeyOffset() + bytesKeySize() + bytesValueSize() + keySize + valueSize;
    }

    @Override
    void doSplitInternal( PageCursor leftCursor, int leftKeyCount, PageCursor rightCursor, int rightKeyCount, int insertPos, KEY newKey,
            long newRightChild, int middlePos, long stableGeneration, long unstableGeneration )
    {
        throw new UnsupportedOperationException( "Implement me" );
    }

    @Override
    void moveKeyValuesFromLeftToRight( PageCursor leftCursor, int leftKeyCount, PageCursor rightCursor, int rightKeyCount,
            int fromPosInLeftNode )
    {
        throw new UnsupportedOperationException( "Implement me" );
    }

    private void setAllocOffset( PageCursor cursor, int allocOffset )
    {
        cursor.setOffset( BYTE_POS_ALLOCOFFSET );
        putKeyOffset( cursor, allocOffset );
    }

    int getAllocOffset( PageCursor cursor )
    {
        cursor.setOffset( BYTE_POS_ALLOCOFFSET );
        return readKeyOffset( cursor );
    }

    private void setDeadSpace( PageCursor cursor, int deadSpace )
    {
        cursor.setOffset( BYTE_POS_DEADSPACE );
        putKeySize( cursor, deadSpace );
    }

    private int getDeadSpace( PageCursor cursor )
    {
        cursor.setOffset( BYTE_POS_DEADSPACE );
        int deadSpace = readKeySize( cursor );
        assert !hasTombstone( deadSpace ) : "Did not expect tombstone in dead space";
        return deadSpace;
    }

    private void placeCursorAtActualKey( PageCursor cursor, int pos, Type type )
    {
        // Set cursor to correct place in offset array
        int keyPosOffset = keyPosOffset( pos, type );
        cursor.setOffset( keyPosOffset );

        // Read actual offset to key
        int keyOffset = readKeyOffset( cursor );

        // Verify offset is reasonable
        if ( keyOffset > pageSize )
        {
            cursor.setCursorException( "Tried to read key on offset " + keyOffset + ". Page size is " + pageSize );
        }

        // Set cursor to actual offset
        cursor.setOffset( keyOffset );
    }

    private int keyPosOffset( int pos, Type type )
    {
        if ( type == LEAF )
        {
            return keyPosOffsetLeaf( pos );
        }
        else
        {
            return keyPosOffsetInternal( pos );
        }
    }

    private int keyPosOffsetLeaf( int pos )
    {
        return HEADER_LENGTH_DYNAMIC + pos * bytesKeyOffset();
    }

    private int keyPosOffsetInternal( int pos )
    {
        // header + childPointer + pos * (keyPosOffsetSize + childPointer)
        return HEADER_LENGTH_DYNAMIC + childSize() + pos * keyChildSize();
    }

    private int keyChildSize()
    {
        return bytesKeyOffset() + SIZE_PAGE_REFERENCE;
    }

    private int childSize()
    {
        return SIZE_PAGE_REFERENCE;
    }

    private static int bytesKeySize()
    {
        return BYTE_SIZE_KEY_SIZE;
    }

    private static int bytesValueSize()
    {
        return BYTE_SIZE_VALUE_SIZE;
    }

    private static int bytesKeyOffset()
    {
        return BYTE_SIZE_OFFSET;
    }

    private static int bytesPageOffset()
    {
        return BYTE_SIZE_OFFSET;
    }

    @Override
    public String toString()
    {
        return "TreeNodeDynamicSize[pageSize:" + pageSize + ", keyValueSizeCap:" + keyValueSizeCap + "]";
    }

    @SuppressWarnings( "unused" )
    void printNode( PageCursor cursor, boolean includeValue, long stableGeneration, long unstableGeneration )
    {
        int currentOffset = cursor.getOffset();
        // [header] <- dont care
        // LEAF:     [allocSpace=][child0,key0*,child1,...][keySize|key][keySize|key]
        // INTERNAL: [allocSpace=][key0*,key1*,...][offset|keySize|valueSize|key][keySize|valueSize|key]

        Type type = isInternal( cursor ) ? INTERNAL : LEAF;

        // HEADER
        int allocSpace = getAllocOffset( cursor );
        String additionalHeader = "[allocSpace=" + allocSpace + "]";

        // OFFSET ARRAY
        String offsetArray = readOffsetArray( cursor, stableGeneration, unstableGeneration, type );

        // KEYS
        KEY readKey = layout.newKey();
        VALUE readValue = layout.newValue();
        StringJoiner keys = new StringJoiner( "][", "[", "]" );
        cursor.setOffset( allocSpace );
        while ( cursor.getOffset() < cursor.getCurrentPageSize() )
        {
            StringJoiner singleKey = new StringJoiner( "|" );
            singleKey.add( Integer.toString( cursor.getOffset() ) );
            int keySize = readKeySize( cursor );
            int valueSize = 0;
            if ( type == LEAF )
            {
                valueSize = readValueSize( cursor );
            }
            if ( hasTombstone( keySize ) )
            {
                singleKey.add( "X" );
                keySize = DynamicSizeUtil.stripTombstone( keySize );
            }
            else
            {
                singleKey.add( "_" );
            }
            layout.readKey( cursor, readKey, keySize );
            if ( type == LEAF )
            {
                layout.readValue( cursor, readValue, valueSize );
            }
            singleKey.add( Integer.toString( keySize ) );
            if ( type == LEAF && includeValue )
            {
                singleKey.add( Integer.toString( valueSize ) );
            }
            singleKey.add( readKey.toString() );
            if ( type == LEAF && includeValue )
            {
                singleKey.add( readValue.toString() );
            }
            keys.add( singleKey.toString() );
        }

        System.out.println( additionalHeader + offsetArray + keys );
        cursor.setOffset( currentOffset );
    }

    private String readOffsetArray( PageCursor cursor, long stableGeneration, long unstableGeneration, Type type )
    {
        int keyCount = keyCount( cursor );
        StringJoiner offsetArray = new StringJoiner( ",", "[", "]" );
        for ( int i = 0; i < keyCount; i++ )
        {
            if ( type == INTERNAL )
            {
                long childPointer = GenerationSafePointerPair.pointer( childAt( cursor, i, stableGeneration, unstableGeneration ) );
                offsetArray.add( "/" + Long.toString( childPointer ) + "\\" );
            }
            cursor.setOffset( keyPosOffset( i, type ) );
            offsetArray.add( Integer.toString( DynamicSizeUtil.readKeyOffset( cursor ) ) );
        }
        if ( type == INTERNAL )
        {
            long childPointer = GenerationSafePointerPair.pointer( childAt( cursor, keyCount, stableGeneration, unstableGeneration ) );
            offsetArray.add( "/" + Long.toString( childPointer ) + "\\" );
        }
        return offsetArray.toString();
    }
}
