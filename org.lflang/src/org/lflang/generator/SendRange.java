/* Abstract class for ranges of NamedInstance. */

/*
Copyright (c) 2019-2021, The University of California at Berkeley.

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright notice,
   this list of conditions and the following disclaimer in the documentation
   and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF
THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
package org.lflang.generator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class representing a range of a port that sources data
 * together with a list of destination ranges of other ports that should all
 * receive the same data sent in this range.
 * All ranges in the destinations list have the same width as this range,
 * but not necessarily the same start offsets.
 * This class also includes a field representing the number of destination
 * reactors.
 * 
 * This class and subclasses are designed to be immutable.
 * Modifications always return a new RuntimeRange.
 *
 * @author{Edward A. Lee <eal@berkeley.edu>}
*/
public class SendRange extends RuntimeRange.Port {
    
    /**
     * Create a new send range.
     * @param instance The instance over which this is a range of.
     * @param start The starting index.
     * @param width The width.
     */
    public SendRange(
            PortInstance instance,
            int start,
            int width
    ) {
        super(instance, start, width, null);
    }

    //////////////////////////////////////////////////////////
    //// Public variables

    /** The list of destination ranges to which this broadcasts. */
    public final List<RuntimeRange<PortInstance>> destinations = new ArrayList<RuntimeRange<PortInstance>>();

    //////////////////////////////////////////////////////////
    //// Public methods

    /**
     * Add a destination to the list of destinations of this range.
     * If the width of the destination is not a multiple of the width
     * of this range, throw an exception.
     * @throws IllegalArgumentException If the width doesn't match.
     */
    public void addDestination(RuntimeRange<PortInstance> dst) {
        if (dst.width % width != 0) {
            throw new IllegalArgumentException(
                    "Destination range width is not a multiple of sender's width");
        }
        destinations.add(dst);
        // Void any precomputed number of destinations.
        _numberOfDestinationReactors = -1;
    }
    
    /**
     * Override the base class to add additional comparisons so that
     * ordering is never ambiguous. This means that sorting will be deterministic.
     * Note that this can return 0 even if equals() does not return true.
     */
    @Override
    public int compareTo(RuntimeRange<?> o) {
        int result = super.compareTo(o);
        if (result == 0) {
            // Longer destination lists come first.
            if (destinations.size() > ((SendRange)o).destinations.size()) {
                return -1;
            } else if (destinations.size() == ((SendRange)o).destinations.size()) {
                return instance.getFullName().compareTo(o.instance.getFullName());
            } else {
                return 1;
            }
        }
        return result;
    }

    /**
     * Return the total number of destination reactors. Specifically, this
     * is the number of distinct reactors that react to messages from this
     * send range.
     */
    public int getNumberOfDestinationReactors() {
        if (_numberOfDestinationReactors < 0) {
            // Has not been calculated before. Calculate now.
            _numberOfDestinationReactors = 0;
            Map<ReactorInstance, Set<Integer>> result = new HashMap<ReactorInstance, Set<Integer>>();
            for (RuntimeRange<PortInstance> destination : this.destinations) {
                // The following set contains unique identifiers the parent reactors
                // of destination ports.
                Set<Integer> parentIDs = destination.parentInstances(1);
                Set<Integer> previousParentIDs = result.get(destination.instance.parent);
                if (previousParentIDs == null) {
                    result.put(destination.instance.parent, parentIDs);
                } else {
                    previousParentIDs.addAll(parentIDs);
                }
            }
            for (ReactorInstance parent : result.keySet()) {
                _numberOfDestinationReactors += result.get(parent).size();
            }
        }
        return _numberOfDestinationReactors;
    }

    /**
     * Return a new SendRange that is identical to this range but
     * with width reduced to the specified width.
     * If the new width is greater than or equal to the width
     * of this range, then return this range.
     * If the newWidth is 0 or negative, return null.
     * This overrides the base class to also apply head()
     * to the destination list.
     * @param newWidth The new width.
     */
    @Override
    public SendRange head(int newWidth) {
        // NOTE: Cannot use the superclass because it returns a RuntimeRange, not a SendRange.
        // Also, cannot return this without applying head() to the destinations.
        if (newWidth <= 0) return null;

        SendRange result = new SendRange(instance, start, newWidth);
        
        for (RuntimeRange<PortInstance> destination : destinations) {
            result.destinations.add(destination.head(newWidth));
        }
        return result;
    }

    /**
     * Return a new SendRange that represents the leftover elements
     * starting at the specified offset. If the offset is greater
     * than or equal to the width, then this returns null.
     * If this offset is 0 then this returns this range unmodified.
     * This overrides the base class to also apply tail()
     * to the destination list.
     * @param offset The number of elements to consume. 
     */
    @Override
    public SendRange tail(int offset) {
        // NOTE: Cannot use the superclass because it returns a RuntimeRange, not a SendRange.
        // Also, cannot return this without applying tail() to the destinations.
        if (offset >= width) return null;
        SendRange result = new SendRange(instance, start + offset, width - offset);

        for (RuntimeRange<PortInstance> destination : destinations) {
            result.destinations.add(destination.tail(offset));
        }
        return result;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append(super.toString());
        result.append("->[");
        List<String> dsts = new LinkedList<String>();
        for (RuntimeRange<PortInstance> dst : destinations) {
            dsts.add(dst.toString());
        }
        result.append(String.join(", ", dsts));
        result.append("]");
        return result.toString();
    }

    //////////////////////////////////////////////////////////
    //// Protected methods

    /**
     * Return a new SendRange that is like this one, but
     * converted to the specified upstream range. The returned
     * SendRange inherits the destinations of this range.
     * The width of the resulting range is
     * the minimum of the two widths.
     * 
     * @param srcRange A new source range.
     */
    protected SendRange newSendRange(RuntimeRange<PortInstance> srcRange) {
        SendRange reference = this;
        if (srcRange.width > width) {
            srcRange = srcRange.head(width);
        } else if (srcRange.width < width) {
            reference = head(srcRange.width);
        }
        SendRange result = new SendRange(srcRange.instance, srcRange.start, srcRange.width);
        
        for (RuntimeRange<PortInstance> dst : reference.destinations) { 
            result.destinations.add(dst.head(srcRange.width));
        }
        return result;
    }

    //////////////////////////////////////////////////////////
    //// Private variables

    private int _numberOfDestinationReactors = -1; // Never access this directly.
}