package org.broadinstitute.sting.oneoffprojects.walkers;

import org.broadinstitute.sting.WalkerTest;
import org.junit.Test;

import java.util.Arrays;

/**
 * check that we're getting the expected results from the RODs for reads for a variety of input types
 */
public class ValidateRODForReadsIntegrationTest extends WalkerTest {

    private final String vcfFile = validationDataLocation + "rodForReadsVCFCheck.vcf";
    private final String dbSNPFile = GATKDataLocation + "dbsnp_129_hg18.rod";
    
     public static String baseTestString1KG() {
            return "-T ValidateRODForReads -o %s -R testdata/exampleFASTA.fasta -I testdata/exampleBAM.bam";
        }


    @Test
    public void testSimpleVCFPileup() {
        WalkerTestSpec spec = new WalkerTestSpec(
                baseTestString1KG() + " -B vcf,vcf," + vcfFile, 1,
                Arrays.asList("f7919e9dc156fb5d3ad0541666864ea5"));
        executeTest("testSimpleVCFPileup", spec);
    }

    @Test
    public void testSimpleDbSNPPileup() {
        WalkerTestSpec spec = new WalkerTestSpec(
                baseTestString1KG() + " -B dbsnp,dbsnp," + dbSNPFile, 1,
                Arrays.asList("c63b8ef9291a450f0519c73ac9cae189"));
        executeTest("testSimpleDbSNPPileup", spec);
    }
}