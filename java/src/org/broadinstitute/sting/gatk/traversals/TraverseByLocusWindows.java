package org.broadinstitute.sting.gatk.traversals;

import org.broadinstitute.sting.gatk.walkers.LocusWindowWalker;
import org.broadinstitute.sting.gatk.walkers.Walker;
import org.broadinstitute.sting.gatk.LocusContext;
import org.broadinstitute.sting.gatk.Reads;
import org.broadinstitute.sting.gatk.refdata.ReferenceOrderedData;
import org.broadinstitute.sting.gatk.refdata.ReferenceOrderedDatum;
import org.broadinstitute.sting.gatk.refdata.RefMetaDataTracker;
import org.broadinstitute.sting.gatk.iterators.ReferenceIterator;
import org.broadinstitute.sting.gatk.iterators.MergingSamRecordIterator2;
import org.broadinstitute.sting.utils.GenomeLoc;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.GenomeLocParser;

import java.util.*;
import java.io.File;

import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMFileReader;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.util.CloseableIterator;
import net.sf.picard.sam.SamFileHeaderMerger;

/**
 * Created by IntelliJ IDEA.
 * User: ebanks
 * Date: Apr 23, 2009
 * Time: 10:26:03 AM
 * To change this template use File | Settings | File Templates.
 */
public class TraverseByLocusWindows extends TraversalEngine {

    public TraverseByLocusWindows(List<File> reads, File ref, List<ReferenceOrderedData<? extends ReferenceOrderedDatum>> rods) {
        super(reads, ref, rods);
    }

    @Override
    public <M,T> T traverse(Walker<M,T> walker, List<GenomeLoc> locations) {
        if ( walker instanceof LocusWindowWalker ) {
            LocusWindowWalker<M, T> locusWindowWalker = (LocusWindowWalker<M, T>)walker;
            T sum = traverseByIntervals(locusWindowWalker, locations);
            return sum;
        } else {
            throw new IllegalArgumentException("Walker isn't an interval walker!");
        }
    }

    /**
     * Traverse by intervals -- the key driver of linearly ordered traversal of intervals.  Provides reads, RODs, and
     * the reference base for each interval in the reference to the intervalWalker walker.  Supports all of the
     * interaction contract implied by the interval walker
     *
     * @param walker An interval walker object
     * @param <M>    MapType -- the result of calling map() on walker
     * @param <T>    ReduceType -- the result of calling reduce() on the walker
     * @return 0 on success
     */
    protected <M, T> T traverseByIntervals(LocusWindowWalker<M, T> walker, List<GenomeLoc> locations) {
        logger.debug("Entering traverseByIntervals");

        if(readsFiles.size() > 1)
            throw new UnsupportedOperationException("Cannot do ByInterval traversal on file with multiple inputs.");        

        samReader = initializeSAMFile(readsFiles.get(0));

        verifySortOrder(true);

        walker.initialize();

        T sum = walker.reduceInit();

        if ( locations.isEmpty() ) {
            logger.debug("There are no intervals provided for the traversal");
        } else {
            if ( !samReader.hasIndex() )
                Utils.scareUser("Processing locations were requested, but no index was found for the input SAM/BAM file. This operation is potentially dangerously slow, aborting.");

            sum = intervalTraversal(walker, locations, sum);
        }

        //printOnTraversalDone("intervals", sum);
        walker.onTraversalDone(sum);
        return sum;
    }

    protected <M, T> T intervalTraversal(LocusWindowWalker<M, T> walker, List<GenomeLoc> locations, T sum) {
        for ( GenomeLoc interval : locations ) {
            logger.debug(String.format("Processing interval %s", interval.toString()));

            CloseableIterator<SAMRecord> readIter = getIteratorOverDesiredRegion( samReader, interval );
            Iterator<SAMRecord> wrappedIter = wrapReadsIterator(readIter, false);
            LocusContext locus = getLocusContext(wrappedIter, interval);
            readIter.close();

            sum = carryWalkerOverInterval(walker, sum, locus);
        }
        return sum;
    }

    private LocusContext getLocusContext(Iterator<SAMRecord> readIter, GenomeLoc interval) {
        ArrayList<SAMRecord> reads = new ArrayList<SAMRecord>();
        boolean done = false;
        long leftmostIndex = interval.getStart(),
                rightmostIndex = interval.getStop();
        while (readIter.hasNext() && !done) {
            TraversalStatistics.nRecords++;


            SAMRecord read = readIter.next();
            reads.add(read);
            if ( read.getAlignmentStart() < leftmostIndex )
                leftmostIndex = read.getAlignmentStart();
            if ( read.getAlignmentEnd() > rightmostIndex )
                rightmostIndex = read.getAlignmentEnd();
            if ( this.maxReads > 0 && TraversalStatistics.nRecords > this.maxReads ) {
                logger.warn(String.format("Maximum number of reads encountered, terminating traversal " + TraversalStatistics.nRecords));
                done = true;
            }
        }

        GenomeLoc window = GenomeLocParser.createGenomeLoc(interval.getContig(), leftmostIndex, rightmostIndex);
        LocusContext locus = new LocusContext(window, reads, null);
        if ( DOWNSAMPLE_BY_COVERAGE )
            locus.downsampleToCoverage(downsamplingCoverage);

        return locus;
    }

    private LocusContext merge(LocusContext locus1, LocusContext locus2) {
        GenomeLoc loc = locus1.getLocation().merge(locus2.getLocation());
        TreeSet<SAMRecord> set = new TreeSet<SAMRecord>(new Comparator<SAMRecord>() {
            public int compare(SAMRecord obj1, SAMRecord obj2) {
                GenomeLoc myLoc = GenomeLocParser.createGenomeLoc(obj1);
                GenomeLoc hisLoc = GenomeLocParser.createGenomeLoc(obj2);
                int comparison = myLoc.compareTo(hisLoc);
                 // if the reads have the same start position, we must give a non-zero comparison
                 // (because java Sets often require "consistency with equals")
                if ( comparison == 0 )
                    comparison = obj1.getReadName().compareTo(obj2.getReadName());
                return comparison;
            }
        });

        set.addAll(locus1.getReads());
        set.addAll(locus2.getReads());
        return new LocusContext(loc, new ArrayList<SAMRecord>(set), null);
    }

    protected <M, T> T carryWalkerOverInterval(LocusWindowWalker<M, T> walker, T sum, LocusContext window) {
        ReferenceIterator refSite = refIter.seekForward(window.getLocation());
        StringBuffer refBases = new StringBuffer(refSite.getBaseAsString());
        int locusLength = (int)(window.getLocation().getStop() - window.getLocation().getStart());
        for ( int i = 0; i < locusLength; i++ ) {
            refSite = refSite.next();
            refBases.append(refSite.getBaseAsChar());
        }
        window.setReferenceContig(refSite.getCurrentContig());

        // Iterate forward to get all reference ordered data covering this interval
        final RefMetaDataTracker tracker = getReferenceOrderedDataAtLocus(window.getLocation());

        sum = walkAtinterval( walker, sum, window, refBases.toString(), tracker );

        printProgress("intervals", window.getLocation());

        return sum;
    }

    protected <M, T> T walkAtinterval( final LocusWindowWalker<M, T> walker,
                                       T sum,
                                       final LocusContext locus,
                                       final String refSeq,
                                       final RefMetaDataTracker tracker ) {

        //
        // Execute our contract with the walker.  Call filter, map, and reduce
        //
        final boolean keepMeP = walker.filter(tracker, refSeq, locus);
        if (keepMeP) {
            M x = walker.map(tracker, refSeq, locus);
            sum = walker.reduce(x, sum);
        }

        //printProgress("intervals", interval.getLocation());
        return sum;
    }

    /**
     * Gets an iterator over the specified region.  Uses a special iterator that dynamically adds a header to all
     * read information.
     * @param reader SAMFileReader to query.
     * @param region Region to use.
     * @return An iterator over the desired region.
     */
    private CloseableIterator<SAMRecord> getIteratorOverDesiredRegion( SAMFileReader reader, GenomeLoc region ) {
        SamFileHeaderMerger headerMerger = new SamFileHeaderMerger( Collections.singletonList(reader), SAMFileHeader.SortOrder.coordinate );
        MergingSamRecordIterator2 iterator = new MergingSamRecordIterator2( headerMerger, new Reads(readsFiles) );
        if( region != null )
            iterator.queryOverlapping( region.getContig(), (int)region.getStart(), (int)region.getStop() );
        return iterator;
    }
}
