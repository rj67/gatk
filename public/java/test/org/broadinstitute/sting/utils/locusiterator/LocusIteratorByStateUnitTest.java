/*
* Copyright (c) 2012 The Broad Institute
* 
* Permission is hereby granted, free of charge, to any person
* obtaining a copy of this software and associated documentation
* files (the "Software"), to deal in the Software without
* restriction, including without limitation the rights to use,
* copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the
* Software is furnished to do so, subject to the following
* conditions:
* 
* The above copyright notice and this permission notice shall be
* included in all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
* EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
* OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
* NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
* HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
* WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
* FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR
* THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.broadinstitute.sting.utils.locusiterator;

import net.sf.samtools.CigarOperator;
import net.sf.samtools.SAMFileHeader;
import net.sf.samtools.SAMRecord;
import org.broadinstitute.sting.gatk.ReadProperties;
import org.broadinstitute.sting.gatk.contexts.AlignmentContext;
import org.broadinstitute.sting.gatk.downsampling.DownsampleType;
import org.broadinstitute.sting.gatk.downsampling.DownsamplingMethod;
import org.broadinstitute.sting.utils.NGSPlatform;
import org.broadinstitute.sting.utils.QualityUtils;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.pileup.PileupElement;
import org.broadinstitute.sting.utils.pileup.ReadBackedPileup;
import org.broadinstitute.sting.utils.sam.ArtificialSAMUtils;
import org.broadinstitute.sting.utils.sam.GATKSAMReadGroupRecord;
import org.broadinstitute.sting.utils.sam.GATKSAMRecord;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.*;

/**
 * testing of the new (non-legacy) version of LocusIteratorByState
 */
public class LocusIteratorByStateUnitTest extends LocusIteratorByStateBaseTest {
    private static final boolean DEBUG = false;
    protected LocusIteratorByState li;

    @Test(enabled = true && ! DEBUG)
    public void testXandEQOperators() {
        final byte[] bases1 = new byte[] {'A','A','A','A','A','A','A','A','A','A'};
        final byte[] bases2 = new byte[] {'A','A','A','C','A','A','A','A','A','C'};

        // create a test version of the Reads object
        ReadProperties readAttributes = createTestReadProperties();

        SAMRecord r1 = ArtificialSAMUtils.createArtificialRead(header,"r1",0,1,10);
        r1.setReadBases(bases1);
        r1.setBaseQualities(new byte[] {20,20,20,20,20,20,20,20,20,20});
        r1.setCigarString("10M");

        SAMRecord r2 = ArtificialSAMUtils.createArtificialRead(header,"r2",0,1,10);
        r2.setReadBases(bases2);
        r2.setBaseQualities(new byte[] {20,20,20,20,20,20,20,20,20,20,20,20});
        r2.setCigarString("3=1X5=1X");

        SAMRecord r3 = ArtificialSAMUtils.createArtificialRead(header,"r3",0,1,10);
        r3.setReadBases(bases2);
        r3.setBaseQualities(new byte[] {20,20,20,20,20,20,20,20,20,20,20,20});
        r3.setCigarString("3=1X5M1X");

        SAMRecord r4  = ArtificialSAMUtils.createArtificialRead(header,"r4",0,1,10);
        r4.setReadBases(bases2);
        r4.setBaseQualities(new byte[] {20,20,20,20,20,20,20,20,20,20});
        r4.setCigarString("10M");

        List<SAMRecord> reads = Arrays.asList(r1, r2, r3, r4);

        // create the iterator by state with the fake reads and fake records
        li = makeLTBS(reads,readAttributes);

        while (li.hasNext()) {
            AlignmentContext context = li.next();
            ReadBackedPileup pileup = context.getBasePileup();
            Assert.assertEquals(pileup.depthOfCoverage(), 4);
        }
    }

    @Test(enabled = true && ! DEBUG)
    public void testIndelsInRegularPileup() {
        final byte[] bases = new byte[] {'A','A','A','A','A','A','A','A','A','A'};
        final byte[] indelBases = new byte[] {'A','A','A','A','C','T','A','A','A','A','A','A'};

        // create a test version of the Reads object
        ReadProperties readAttributes = createTestReadProperties();

        SAMRecord before = ArtificialSAMUtils.createArtificialRead(header,"before",0,1,10);
        before.setReadBases(bases);
        before.setBaseQualities(new byte[] {20,20,20,20,20,20,20,20,20,20});
        before.setCigarString("10M");

        SAMRecord during = ArtificialSAMUtils.createArtificialRead(header,"during",0,2,10);
        during.setReadBases(indelBases);
        during.setBaseQualities(new byte[] {20,20,20,20,20,20,20,20,20,20,20,20});
        during.setCigarString("4M2I6M");

        SAMRecord after  = ArtificialSAMUtils.createArtificialRead(header,"after",0,3,10);
        after.setReadBases(bases);
        after.setBaseQualities(new byte[] {20,20,20,20,20,20,20,20,20,20});
        after.setCigarString("10M");

        List<SAMRecord> reads = Arrays.asList(before, during, after);

        // create the iterator by state with the fake reads and fake records
        li = makeLTBS(reads,readAttributes);

        boolean foundIndel = false;
        while (li.hasNext()) {
            AlignmentContext context = li.next();
            ReadBackedPileup pileup = context.getBasePileup().getBaseFilteredPileup(10);
            for (PileupElement p : pileup) {
                if (p.isBeforeInsertion()) {
                    foundIndel = true;
                    Assert.assertEquals(p.getLengthOfImmediatelyFollowingIndel(), 2, "Wrong event length");
                    Assert.assertEquals(p.getBasesOfImmediatelyFollowingInsertion(), "CT", "Inserted bases are incorrect");
                    break;
               }
            }

         }

         Assert.assertTrue(foundIndel,"Indel in pileup not found");
    }

    @Test(enabled = false && ! DEBUG)
    public void testWholeIndelReadInIsolation() {
        final int firstLocus = 44367789;

        // create a test version of the Reads object
        ReadProperties readAttributes = createTestReadProperties();

        SAMRecord indelOnlyRead = ArtificialSAMUtils.createArtificialRead(header, "indelOnly", 0, firstLocus, 76);
        indelOnlyRead.setReadBases(Utils.dupBytes((byte)'A',76));
        indelOnlyRead.setBaseQualities(Utils.dupBytes((byte) '@', 76));
        indelOnlyRead.setCigarString("76I");

        List<SAMRecord> reads = Arrays.asList(indelOnlyRead);

        // create the iterator by state with the fake reads and fake records
        li = makeLTBS(reads, readAttributes);

        // Traditionally, reads that end with indels bleed into the pileup at the following locus.  Verify that the next pileup contains this read
        // and considers it to be an indel-containing read.
        Assert.assertTrue(li.hasNext(),"Should have found a whole-indel read in the normal base pileup without extended events enabled");
        AlignmentContext alignmentContext = li.next();
        Assert.assertEquals(alignmentContext.getLocation().getStart(), firstLocus, "Base pileup is at incorrect location.");
        ReadBackedPileup basePileup = alignmentContext.getBasePileup();
        Assert.assertEquals(basePileup.getReads().size(),1,"Pileup is of incorrect size");
        Assert.assertSame(basePileup.getReads().get(0), indelOnlyRead, "Read in pileup is incorrect");
    }

    /**
     * Test to make sure that reads supporting only an indel (example cigar string: 76I) do
     * not negatively influence the ordering of the pileup.
     */
    @Test(enabled = true && ! DEBUG)
    public void testWholeIndelRead() {
        final int firstLocus = 44367788, secondLocus = firstLocus + 1;

        SAMRecord leadingRead = ArtificialSAMUtils.createArtificialRead(header,"leading",0,firstLocus,76);
        leadingRead.setReadBases(Utils.dupBytes((byte)'A',76));
        leadingRead.setBaseQualities(Utils.dupBytes((byte)'@',76));
        leadingRead.setCigarString("1M75I");

        SAMRecord indelOnlyRead = ArtificialSAMUtils.createArtificialRead(header,"indelOnly",0,secondLocus,76);
        indelOnlyRead.setReadBases(Utils.dupBytes((byte) 'A', 76));
        indelOnlyRead.setBaseQualities(Utils.dupBytes((byte)'@',76));
        indelOnlyRead.setCigarString("76I");

        SAMRecord fullMatchAfterIndel = ArtificialSAMUtils.createArtificialRead(header,"fullMatch",0,secondLocus,76);
        fullMatchAfterIndel.setReadBases(Utils.dupBytes((byte)'A',76));
        fullMatchAfterIndel.setBaseQualities(Utils.dupBytes((byte)'@',76));
        fullMatchAfterIndel.setCigarString("75I1M");

        List<SAMRecord> reads = Arrays.asList(leadingRead, indelOnlyRead, fullMatchAfterIndel);

        // create the iterator by state with the fake reads and fake records
        li = makeLTBS(reads, createTestReadProperties());
        int currentLocus = firstLocus;
        int numAlignmentContextsFound = 0;

        while(li.hasNext()) {
            AlignmentContext alignmentContext = li.next();
            Assert.assertEquals(alignmentContext.getLocation().getStart(),currentLocus,"Current locus returned by alignment context is incorrect");

            if(currentLocus == firstLocus) {
                List<GATKSAMRecord> readsAtLocus = alignmentContext.getBasePileup().getReads();
                Assert.assertEquals(readsAtLocus.size(),1,"Wrong number of reads at locus " + currentLocus);
                Assert.assertSame(readsAtLocus.get(0),leadingRead,"leadingRead absent from pileup at locus " + currentLocus);
            }
            else if(currentLocus == secondLocus) {
                List<GATKSAMRecord> readsAtLocus = alignmentContext.getBasePileup().getReads();
                Assert.assertEquals(readsAtLocus.size(),1,"Wrong number of reads at locus " + currentLocus);
                Assert.assertSame(readsAtLocus.get(0),fullMatchAfterIndel,"fullMatchAfterIndel absent from pileup at locus " + currentLocus);
            }

            currentLocus++;
            numAlignmentContextsFound++;
        }

        Assert.assertEquals(numAlignmentContextsFound, 2, "Found incorrect number of alignment contexts");
    }

    /**
     * Test to make sure that reads supporting only an indel (example cigar string: 76I) are represented properly
     */
    @Test(enabled = false && ! DEBUG)
    public void testWholeIndelReadRepresentedTest() {
        final int firstLocus = 44367788, secondLocus = firstLocus + 1;

        SAMRecord read1 = ArtificialSAMUtils.createArtificialRead(header,"read1",0,secondLocus,1);
        read1.setReadBases(Utils.dupBytes((byte) 'A', 1));
        read1.setBaseQualities(Utils.dupBytes((byte) '@', 1));
        read1.setCigarString("1I");

        List<SAMRecord> reads = Arrays.asList(read1);

        // create the iterator by state with the fake reads and fake records
        li = makeLTBS(reads, createTestReadProperties());

        while(li.hasNext()) {
            AlignmentContext alignmentContext = li.next();
            ReadBackedPileup p = alignmentContext.getBasePileup();
            Assert.assertTrue(p.getNumberOfElements() == 1);
            // TODO -- fix tests
//            PileupElement pe = p.iterator().next();
//            Assert.assertTrue(pe.isBeforeInsertion());
//            Assert.assertFalse(pe.isAfterInsertion());
//            Assert.assertEquals(pe.getBasesOfImmediatelyFollowingInsertion(), "A");
        }

        SAMRecord read2 = ArtificialSAMUtils.createArtificialRead(header,"read2",0,secondLocus,10);
        read2.setReadBases(Utils.dupBytes((byte) 'A', 10));
        read2.setBaseQualities(Utils.dupBytes((byte) '@', 10));
        read2.setCigarString("10I");

        reads = Arrays.asList(read2);

        // create the iterator by state with the fake reads and fake records
        li = makeLTBS(reads, createTestReadProperties());

        while(li.hasNext()) {
            AlignmentContext alignmentContext = li.next();
            ReadBackedPileup p = alignmentContext.getBasePileup();
            Assert.assertTrue(p.getNumberOfElements() == 1);
            // TODO -- fix tests
//            PileupElement pe = p.iterator().next();
//            Assert.assertTrue(pe.isBeforeInsertion());
//            Assert.assertFalse(pe.isAfterInsertion());
//            Assert.assertEquals(pe.getBasesOfImmediatelyFollowingInsertion(), "AAAAAAAAAA");
        }
    }


    /////////////////////////////////////////////
    // get event length and bases calculations //
    /////////////////////////////////////////////

    @DataProvider(name = "IndelLengthAndBasesTest")
    public Object[][] makeIndelLengthAndBasesTest() {
        final String EVENT_BASES = "ACGTACGTACGT";
        final List<Object[]> tests = new LinkedList<Object[]>();

        for ( int eventSize = 1; eventSize < 10; eventSize++ ) {
            for ( final CigarOperator indel : Arrays.asList(CigarOperator.D, CigarOperator.I) ) {
                final String cigar = String.format("2M%d%s1M", eventSize, indel.toString());
                final String eventBases = indel == CigarOperator.D ? "" : EVENT_BASES.substring(0, eventSize);
                final int readLength = 3 + eventBases.length();

                GATKSAMRecord read = ArtificialSAMUtils.createArtificialRead(header, "read", 0, 1, readLength);
                read.setReadBases(("TT" + eventBases + "A").getBytes());
                final byte[] quals = new byte[readLength];
                for ( int i = 0; i < readLength; i++ )
                    quals[i] = (byte)(i % QualityUtils.MAX_QUAL_SCORE);
                read.setBaseQualities(quals);
                read.setCigarString(cigar);

                tests.add(new Object[]{read, indel, eventSize, eventBases.equals("") ? null : eventBases});
            }
        }

        return tests.toArray(new Object[][]{});
    }

    @Test(enabled = true && ! DEBUG, dataProvider = "IndelLengthAndBasesTest")
    public void testIndelLengthAndBasesTest(GATKSAMRecord read, final CigarOperator op, final int eventSize, final String eventBases) {
        // create the iterator by state with the fake reads and fake records
        li = makeLTBS(Arrays.asList((SAMRecord)read), createTestReadProperties());

        Assert.assertTrue(li.hasNext());

        final PileupElement firstMatch = getFirstPileupElement(li.next());

        Assert.assertEquals(firstMatch.getLengthOfImmediatelyFollowingIndel(), 0, "Length != 0 for site not adjacent to indel");
        Assert.assertEquals(firstMatch.getBasesOfImmediatelyFollowingInsertion(), null, "Getbases of following event should be null at non-adajenct event");

        Assert.assertTrue(li.hasNext());

        final PileupElement pe = getFirstPileupElement(li.next());

        if ( op == CigarOperator.D )
            Assert.assertTrue(pe.isBeforeDeletionStart());
        else
            Assert.assertTrue(pe.isBeforeInsertion());

        Assert.assertEquals(pe.getLengthOfImmediatelyFollowingIndel(), eventSize, "Length of event failed");
        Assert.assertEquals(pe.getBasesOfImmediatelyFollowingInsertion(), eventBases, "Getbases of following event failed");
    }

    private PileupElement getFirstPileupElement(final AlignmentContext context) {
        final ReadBackedPileup p = context.getBasePileup();
        Assert.assertEquals(p.getNumberOfElements(), 1);
        return p.iterator().next();
    }

    ////////////////////////////////////////////
    // comprehensive LIBS/PileupElement tests //
    ////////////////////////////////////////////

    @DataProvider(name = "LIBSTest")
    public Object[][] makeLIBSTest() {
        final List<Object[]> tests = new LinkedList<Object[]>();

//        tests.add(new Object[]{new LIBSTest("2=2D2=2X", 1)});
//        return tests.toArray(new Object[][]{});

        return createLIBSTests(
                Arrays.asList(1, 2),
                Arrays.asList(1, 2, 3, 4));

//        return createLIBSTests(
//                Arrays.asList(2),
//                Arrays.asList(3));
    }

    @Test(enabled = true, dataProvider = "LIBSTest")
    public void testLIBS(LIBSTest params) {
        // create the iterator by state with the fake reads and fake records
        final GATKSAMRecord read = params.makeRead();
        li = makeLTBS(Arrays.asList((SAMRecord)read), createTestReadProperties());
        final LIBS_position tester = new LIBS_position(read);

        int bpVisited = 0;
        int lastOffset = 0;
        while ( li.hasNext() ) {
            bpVisited++;

            AlignmentContext alignmentContext = li.next();
            ReadBackedPileup p = alignmentContext.getBasePileup();
            Assert.assertEquals(p.getNumberOfElements(), 1);
            PileupElement pe = p.iterator().next();

            Assert.assertEquals(p.getNumberOfDeletions(), pe.isDeletion() ? 1 : 0, "wrong number of deletions in the pileup");
            Assert.assertEquals(p.getNumberOfMappingQualityZeroReads(), pe.getRead().getMappingQuality() == 0 ? 1 : 0, "wront number of mapq reads in the pileup");

            tester.stepForwardOnGenome();

            if ( ! hasNeighboringPaddedOps(params.getElements(), pe.getCurrentCigarOffset()) ) {
                Assert.assertEquals(pe.isBeforeDeletionStart(), tester.isBeforeDeletionStart, "before deletion start failure");
                Assert.assertEquals(pe.isAfterDeletionEnd(), tester.isAfterDeletionEnd, "after deletion end failure");
            }

            Assert.assertEquals(pe.isBeforeInsertion(), tester.isBeforeInsertion, "before insertion failure");
            Assert.assertEquals(pe.isAfterInsertion(), tester.isAfterInsertion, "after insertion failure");
            Assert.assertEquals(pe.isNextToSoftClip(), tester.isNextToSoftClip, "next to soft clip failure");

            Assert.assertTrue(pe.getOffset() >= lastOffset, "Somehow read offsets are decreasing: lastOffset " + lastOffset + " current " + pe.getOffset());
            Assert.assertEquals(pe.getOffset(), tester.getCurrentReadOffset(), "Read offsets are wrong at " + bpVisited);

            Assert.assertEquals(pe.getCurrentCigarElement(), read.getCigar().getCigarElement(tester.currentOperatorIndex), "CigarElement index failure");
            Assert.assertEquals(pe.getOffsetInCurrentCigar(), tester.getCurrentPositionOnOperatorBase0(), "CigarElement index failure");

            Assert.assertEquals(read.getCigar().getCigarElement(pe.getCurrentCigarOffset()), pe.getCurrentCigarElement(), "Current cigar element isn't what we'd get from the read itself");

            Assert.assertTrue(pe.getOffsetInCurrentCigar() >= 0, "Offset into current cigar too small");
            Assert.assertTrue(pe.getOffsetInCurrentCigar() < pe.getCurrentCigarElement().getLength(), "Offset into current cigar too big");

            Assert.assertEquals(pe.getOffset(), tester.getCurrentReadOffset(), "Read offset failure");
            lastOffset = pe.getOffset();
        }

        final int expectedBpToVisit = read.getAlignmentEnd() - read.getAlignmentStart() + 1;
        Assert.assertEquals(bpVisited, expectedBpToVisit, "Didn't visit the expected number of bp");
    }

    // ------------------------------------------------------------
    //
    // Tests for keeping reads
    //
    // ------------------------------------------------------------

    @DataProvider(name = "LIBSKeepSubmittedReads")
    public Object[][] makeLIBSKeepSubmittedReads() {
        final List<Object[]> tests = new LinkedList<Object[]>();

        for ( final boolean doSampling : Arrays.asList(true, false) ) {
            for ( final int nReadsPerLocus : Arrays.asList(1, 10) ) {
                for ( final int nLoci : Arrays.asList(1, 10, 25) ) {
                    for ( final int nSamples : Arrays.asList(1, 2, 10) ) {
                        for ( final boolean keepReads : Arrays.asList(true, false) ) {
                            for ( final boolean grabReadsAfterEachCycle : Arrays.asList(true, false) ) {
//        for ( final int nReadsPerLocus : Arrays.asList(1) ) {
//            for ( final int nLoci : Arrays.asList(1) ) {
//                for ( final int nSamples : Arrays.asList(1) ) {
//                    for ( final boolean keepReads : Arrays.asList(true) ) {
//                        for ( final boolean grabReadsAfterEachCycle : Arrays.asList(true) ) {
                                tests.add(new Object[]{nReadsPerLocus, nLoci, nSamples, keepReads, grabReadsAfterEachCycle, doSampling});
                            }
                        }
                    }
                }
            }
        }

        return tests.toArray(new Object[][]{});
    }

    @Test(enabled = true && ! DEBUG, dataProvider = "LIBSKeepSubmittedReads")
    public void testLIBSKeepSubmittedReads(final int nReadsPerLocus,
                                           final int nLoci,
                                           final int nSamples,
                                           final boolean keepReads,
                                           final boolean grabReadsAfterEachCycle,
                                           final boolean downsample) {
        logger.warn(String.format("testLIBSKeepSubmittedReads %d %d %d %b %b %b", nReadsPerLocus, nLoci, nSamples, keepReads, grabReadsAfterEachCycle, downsample));
        final int readLength = 10;

        final SAMFileHeader header = ArtificialSAMUtils.createArtificialSamHeader(1, 1, 100000);
        final List<String> samples = new ArrayList<String>(nSamples);
        for ( int i = 0; i < nSamples; i++ ) {
            final GATKSAMReadGroupRecord rg = new GATKSAMReadGroupRecord("rg" + i);
            final String sample = "sample" + i;
            samples.add(sample);
            rg.setSample(sample);
            rg.setPlatform(NGSPlatform.ILLUMINA.getDefaultPlatform());
            header.addReadGroup(rg);
        }

        final int maxCoveragePerSampleAtLocus = nReadsPerLocus * readLength / 2;
        final int maxDownsampledCoverage = Math.max(maxCoveragePerSampleAtLocus / 2, 1);
        final DownsamplingMethod downsampler = downsample
                ? new DownsamplingMethod(DownsampleType.BY_SAMPLE, maxDownsampledCoverage, null, false)
                : new DownsamplingMethod(DownsampleType.NONE, null, null, false);
        final List<SAMRecord> reads = ArtificialSAMUtils.createReadStream(nReadsPerLocus, nLoci, header, 1, readLength);
        li = new LocusIteratorByState(new FakeCloseableIterator<SAMRecord>(reads.iterator()),
                createTestReadProperties(downsampler, keepReads),
                genomeLocParser,
                samples);

        final Set<SAMRecord> seenSoFar = new HashSet<SAMRecord>();
        final Set<SAMRecord> keptReads = new HashSet<SAMRecord>();
        int bpVisited = 0;
        while ( li.hasNext() ) {
            bpVisited++;
            final AlignmentContext alignmentContext = li.next();
            final ReadBackedPileup p = alignmentContext.getBasePileup();

            if ( downsample ) {
                // just not a safe test
                //Assert.assertTrue(p.getNumberOfElements() <= maxDownsampledCoverage * nSamples, "Too many reads at locus after downsampling");
            } else {
                final int minPileupSize = nReadsPerLocus * nSamples;
                Assert.assertTrue(p.getNumberOfElements() >= minPileupSize);
            }

            seenSoFar.addAll(p.getReads());
            if ( keepReads && grabReadsAfterEachCycle ) {
                final List<SAMRecord> locusReads = li.transferReadsFromAllPreviousPileups();

                // the number of reads starting here
                int nReadsStartingHere = 0;
                for ( final SAMRecord read : p.getReads() )
                    if ( read.getAlignmentStart() == alignmentContext.getPosition() )
                        nReadsStartingHere++;

                if ( downsample )
                    // with downsampling we might have some reads here that were downsampled away
                    // in the pileup
                    Assert.assertTrue(locusReads.size() >= nReadsStartingHere);
                else
                    Assert.assertEquals(locusReads.size(), nReadsStartingHere);
                keptReads.addAll(locusReads);

                // check that all reads we've seen so far are in our keptReads
                for ( final SAMRecord read : seenSoFar ) {
                    Assert.assertTrue(keptReads.contains(read), "A read that appeared in a pileup wasn't found in the kept reads: " + read);
                }
            }

            if ( ! keepReads )
                Assert.assertTrue(li.getReadsFromAllPreviousPileups().isEmpty(), "Not keeping reads but the underlying list of reads isn't empty");
        }

        if ( keepReads && ! grabReadsAfterEachCycle )
            keptReads.addAll(li.transferReadsFromAllPreviousPileups());

        if ( ! downsample ) { // downsampling may drop loci
            final int expectedBpToVisit = nLoci + readLength - 1;
            Assert.assertEquals(bpVisited, expectedBpToVisit, "Didn't visit the expected number of bp");
        }

        if ( keepReads ) {
            // check we have the right number of reads
            final int totalReads = nLoci * nReadsPerLocus * nSamples;
            if ( ! downsample ) { // downsampling may drop reads
                Assert.assertEquals(keptReads.size(), totalReads, "LIBS didn't keep the right number of reads during the traversal");

                // check that the order of reads is the same as in our read list
                for ( int i = 0; i < reads.size(); i++ ) {
                    final SAMRecord inputRead = reads.get(i);
                    final SAMRecord keptRead = reads.get(i);
                    Assert.assertSame(keptRead, inputRead, "Input reads and kept reads differ at position " + i);
                }
            } else {
                Assert.assertTrue(keptReads.size() <= totalReads, "LIBS didn't keep the right number of reads during the traversal");
            }

            // check uniqueness
            final Set<String> readNames = new HashSet<String>();
            for ( final SAMRecord read : keptReads ) {
                Assert.assertFalse(readNames.contains(read.getReadName()), "Found duplicate reads in the kept reads");
                readNames.add(read.getReadName());
            }

            // check that all reads we've seen are in our keptReads
            for ( final SAMRecord read : seenSoFar ) {
                Assert.assertTrue(keptReads.contains(read), "A read that appeared in a pileup wasn't found in the kept reads: " + read);
            }
        }
    }
}
