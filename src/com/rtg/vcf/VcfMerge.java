/*
 * Copyright (c) 2014. Real Time Genomics Limited.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.rtg.vcf;


import static com.rtg.launcher.CommonFlags.FILE;
import static com.rtg.launcher.CommonFlags.NO_GZIP;
import static com.rtg.launcher.CommonFlags.OUTPUT_FLAG;
import static com.rtg.launcher.CommonFlags.STRING;
import static com.rtg.launcher.CommonFlags.STRING_OR_FILE;
import static com.rtg.util.cli.CommonFlagCategories.INPUT_OUTPUT;
import static com.rtg.util.cli.CommonFlagCategories.UTILITY;
import static com.rtg.vcf.VcfUtils.FORMAT_GENOTYPE;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.rtg.launcher.AbstractCli;
import com.rtg.launcher.CommonFlags;
import com.rtg.sam.SamRangeUtils;
import com.rtg.tabix.TabixIndexReader;
import com.rtg.tabix.TabixIndexer;
import com.rtg.tabix.TabixLineReader;
import com.rtg.util.cli.CFlags;
import com.rtg.util.cli.CommonFlagCategories;
import com.rtg.util.cli.Flag;
import com.rtg.util.cli.Validator;
import com.rtg.util.diagnostic.Diagnostic;
import com.rtg.util.diagnostic.NoTalkbackSlimException;
import com.rtg.util.intervals.ReferenceRanges;
import com.rtg.util.io.FileUtils;
import com.rtg.vcf.header.ContigField;
import com.rtg.vcf.header.VcfHeader;
import com.rtg.vcf.header.VcfHeaderMerge;

/**
 * Merge multiple <code>VCF</code> files. Assumes both files are sorted in the same manner
 */
public class VcfMerge extends AbstractCli {

  private static final String ADD_HEADER_FLAG = "add-header";
  private static final String FORCE_MERGE = "force-merge";
  private static final String FORCE_MERGE_ALL = "force-merge-all";
  private static final String PRESERVE_FORMATS = "preserve-formats";
  private static final String STATS_FLAG = "stats";
  private static final String NO_ALT_MERGE = "no-merge-alts";
  private static final String NO_RECORD_MERGE = "no-merge-records";
  private static final String NON_PADDING_AWARE = "Xnon-padding-aware";
  private static final String GT_MAJORITY = "Xgt-majority";

  @Override
  public String moduleName() {
    return "vcfmerge";
  }

  @Override
  public String description() {
    return "merge single-sample VCF files into a single multi-sample VCF";
  }

  @Override
  protected void initFlags() {
    CommonFlagCategories.setCategories(mFlags);
    CommonFlags.initNoGzip(mFlags);
    CommonFlags.initIndexFlags(mFlags);
    CommonFlags.initForce(mFlags);
    mFlags.setDescription("Merge a one or more input VCF files to a single output VCF. Use cases include combining single sample calls into a multi-sample VCF, or to combine calls on separate chromosomes into a single VCF.");
    mFlags.registerRequired('o', OUTPUT_FLAG, File.class, FILE, "output VCF file. Use '-' to write to standard output").setCategory(INPUT_OUTPUT);
    CommonFlags.initRegionOrBedRegionsFlags(mFlags);
    mFlags.registerOptional(CommonFlags.NO_HEADER, "prevent VCF header from being written").setCategory(UTILITY);
    initAddHeaderFlag(mFlags);
    final Flag<File> inFlag = mFlags.registerRequired(File.class, FILE, "input VCF files to merge").setCategory(INPUT_OUTPUT).setMinCount(0).setMaxCount(Integer.MAX_VALUE);
    final Flag<File> listFlag = mFlags.registerOptional('I', CommonFlags.INPUT_LIST_FLAG, File.class, FILE, "file containing a list of VCF format files (1 per line) to be merged").setCategory(INPUT_OUTPUT);
    mFlags.registerOptional('F', FORCE_MERGE_ALL, "attempt merging of all non-matching header declarations").setCategory(UTILITY);
    mFlags.registerOptional('f', FORCE_MERGE, String.class, STRING, "allow merging of specified header ID even when descriptions do not match").setCategory(UTILITY).setMinCount(0).setMaxCount(Integer.MAX_VALUE).enableCsv();
    mFlags.registerOptional(PRESERVE_FORMATS, "do not merge multiple records containing unmergeable FORMAT fields (Default is to remove those FORMAT fields so the variants can be combined)").setCategory(UTILITY);
    mFlags.registerOptional(STATS_FLAG, "output statistics for the merged VCF file").setCategory(UTILITY);
    mFlags.registerOptional(NO_RECORD_MERGE, "do not merge multiple records at the same position into one").setCategory(UTILITY);
    mFlags.registerOptional(NO_ALT_MERGE, "do not merge multiple records if the ALTs are different").setCategory(UTILITY);
    mFlags.registerOptional(NON_PADDING_AWARE, "allow merging of multiple records that mix whether they employ a VCF anchor base").setCategory(UTILITY);
    mFlags.registerOptional(GT_MAJORITY, "alternate mode that combines per sample GTs by majority vote").setCategory(UTILITY);

    mFlags.addRequiredSet(inFlag);
    mFlags.addRequiredSet(listFlag);

    mFlags.setValidator(new VcfMergeValidator());
  }

  private static class VcfMergeValidator implements Validator {
    @Override
    public boolean isValid(final CFlags flags) {
      return CommonFlags.checkFileList(flags, CommonFlags.INPUT_LIST_FLAG, null, Integer.MAX_VALUE)
        && CommonFlags.validateOutputFile(flags, VcfUtils.getZippedVcfFileName(!flags.isSet(NO_GZIP), (File) flags.getValue(OUTPUT_FLAG)))
        && flags.checkAtMostOne(GT_MAJORITY, NO_ALT_MERGE, NO_RECORD_MERGE)
        && flags.checkNand(FORCE_MERGE_ALL, FORCE_MERGE);
    }
  }

  static void initAddHeaderFlag(CFlags flags) {
    flags.registerOptional('a', ADD_HEADER_FLAG, String.class, STRING_OR_FILE, "file containing VCF header lines to add, or a literal header line")
      .setMaxCount(Integer.MAX_VALUE)
      .setCategory(UTILITY);
  }

  static Collection<String> getHeaderLines(CFlags flags) throws IOException {
    final List<String> extraHeaderLines = new ArrayList<>();
    if (flags.isSet(ADD_HEADER_FLAG)) {
      for (final Object o : flags.getValues(ADD_HEADER_FLAG)) {
        final String lineOrFile = (String) o;
        if (lineOrFile.length() > 0) {
          if (VcfHeader.isMetaLine(lineOrFile)) {
            extraHeaderLines.add(lineOrFile);
          } else if (new File(lineOrFile).exists()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(FileUtils.createInputStream(new File(lineOrFile), false)))) {
              String line;
              while ((line = r.readLine()) != null) {
                if (line.length() > 0) {
                  if (VcfHeader.isMetaLine(line)) {
                    extraHeaderLines.add(line);
                  } else {
                    throw new VcfFormatException("In additional header file " + lineOrFile + ", line '" + line + "', doesn't look like a VCF header line");
                  }
                }
              }
            }
          } else {
            throw new VcfFormatException("Additional header argument '" + lineOrFile + "', doesn't look like a VCF header line or a file");
          }
        }
      }
    }
    return extraHeaderLines;
  }

  @Override
  protected int mainExec(final OutputStream out, final PrintStream err) throws IOException {
    final File outFile = (File) mFlags.getValue(OUTPUT_FLAG);
    final List<File> inputs = CommonFlags.getFileList(mFlags, CommonFlags.INPUT_LIST_FLAG, null, false);
    final HashSet<File> dupTest = new HashSet<>();
    for (final File f : inputs) {
      if (!dupTest.add(f.getCanonicalFile())) {
        throw new NoTalkbackSlimException("File: " + f.getPath() + " is specified more than once.");
      }
    }
    final Collection<String> extraHeaderLines = getHeaderLines(mFlags);
    final HashSet<String> forceMerge;
    if (mFlags.isSet(FORCE_MERGE_ALL)) {
      forceMerge = null;
    } else {
      final List<?> forceMergeRaw = mFlags.getValues(FORCE_MERGE);
      forceMerge = new HashSet<>();
      for (final Object o : forceMergeRaw) {
        forceMerge.add((String) o);
      }
    }
    final boolean gzip = !mFlags.isSet(NO_GZIP);
    final VariantStatistics stats = mFlags.isSet(STATS_FLAG) ? new VariantStatistics(null) : null;
    final ReferenceRanges<String> regions = CommonFlags.parseRegionOrBedRegions(mFlags);
    final VcfPositionZipper posZip = new VcfPositionZipper(regions, forceMerge, inputs.toArray(new File[0]));
    final VcfHeader header = posZip.getHeader();
    VcfUtils.addHeaderLines(header, extraHeaderLines);

    String defaultFormat = FORMAT_GENOTYPE;
    if (header.getFormatField(FORMAT_GENOTYPE) == null && header.getFormatLines().size() > 0) {
      defaultFormat = header.getFormatLines().get(0).getId();
    }

    final boolean stdout = FileUtils.isStdio(outFile);
    final File vcfFile = VcfUtils.getZippedVcfFileName(gzip, outFile);
    final VcfRecordMerger merger;
    if (mFlags.isSet(GT_MAJORITY)) {
      if (!FORMAT_GENOTYPE.equals(defaultFormat)) {
        throw new NoTalkbackSlimException("VCF header does not contain GT declaration");
      }
      merger = new VcfGtMajorityMerger();
    } else if (mFlags.isSet(NO_ALT_MERGE)) {
      merger = new VcfSameAltsMerger();
    } else {
      merger = new VcfRecordMerger();
    }
    merger.setHeader(header);
    merger.setDefaultFormat(defaultFormat);
    merger.setAllowMerging(!mFlags.isSet(NO_RECORD_MERGE));
    merger.setPaddingAware(!mFlags.isSet(NON_PADDING_AWARE));
    merger.setDropUnmergeable(!mFlags.isSet(PRESERVE_FORMATS));

    try (final VcfWriter w = new VcfWriterFactory(mFlags).addRunInfo(true).make(header, vcfFile)) {
      final ZipperCallback callback = (records, headers) -> {
        assert records.length > 0;
        final VcfRecord[] mergedArr = merger.mergeRecords(records, headers);
        for (VcfRecord merged : mergedArr) {
          if (stats != null) {
            stats.tallyVariant(header, merged);
          }
          w.write(merged);
        }
      };
      while (posZip.hasNextPosition()) {
        posZip.nextPosition(callback);
      }
    } catch (final VcfFormatException iae) {
      throw new NoTalkbackSlimException("Problem in VCF: " + iae.getMessage());
    }
    merger.printWarningSummary();
    if (!stdout) {
      if (stats != null) {
        stats.printStatistics(out);
      }
    }
    return 0;
  }

  /**
   * This class will process multiple <code>VCF</code> files in order and call the appropriate callback
   * for each chromosome position encountered in any of the files. The callback receives all records that
   * are present on that position (provided there is only 1 per file)
   */
  static class VcfPositionZipper implements Closeable {
    final File[] mFiles;
    final VcfHeader[] mHeaders;
    final TabixIndexReader[] mIndexes;
    final List<ReferenceRanges<String>> mRegions;
    final VcfReader[] mReaders;
    private final VcfHeader mMergedHeader;
    private int mCurrentRegion = 0;
    private final Set<Integer> mCurrentRecords = new HashSet<>();

    VcfPositionZipper(ReferenceRanges<String> rr, File... vcfFiles) throws IOException {
      this(rr, null, vcfFiles);
    }
    VcfPositionZipper(ReferenceRanges<String> rr, Set<String> forceMerge, File... vcfFiles) throws IOException {
      mFiles = vcfFiles;
      mReaders = new VcfReader[mFiles.length];
      mHeaders = new VcfHeader[mFiles.length];
      mIndexes = new TabixIndexReader[mFiles.length];
      VcfHeader current = null;
      int numSamples = 0;
      boolean warnNumSamples = true;
      for (int i = 0; i < mFiles.length; ++i) {
        final File vcfFile = mFiles[i];
        final VcfHeader header = VcfUtils.getHeader(vcfFile);
        mHeaders[i] = header;
        if (current != null) {
          current = VcfHeaderMerge.mergeHeaders(current, header, forceMerge);
          if (current.getNumberOfSamples() != numSamples && warnNumSamples) {
            Diagnostic.warning("When merging multiple samples the QUAL, FILTER, and INFO fields are taken from the first record at each position.");
            warnNumSamples = false;
          }
        } else {
          current = header;
          numSamples = current.getNumberOfSamples();
        }
        final File index = TabixIndexer.indexFileName(vcfFile);
        if (!TabixIndexer.isBlockCompressed(vcfFile)) {
          throw new NoTalkbackSlimException(vcfFile + " is not in bgzip format");
        } else if (!index.exists()) {
          throw new NoTalkbackSlimException("Index not found for file: " + index.getPath() + " expected index called: " + index.getPath());
        }
        mIndexes[i] = new TabixIndexReader(TabixIndexer.indexFileName(vcfFile));
      }
      mMergedHeader = current;

      if (rr == null) {
        final LinkedHashSet<String> chroms = new LinkedHashSet<>();
        if (!mMergedHeader.getContigLines().isEmpty()) {
          for (final ContigField cf : mMergedHeader.getContigLines()) {
            chroms.add(cf.getId());
          }
        }
        for (int i = 0; i < vcfFiles.length; ++i) {
          chroms.addAll(Arrays.asList(mIndexes[i].sequenceNames()));
        }
        final ReferenceRanges<String> rrr = SamRangeUtils.createExplicitReferenceRange(chroms);
        mRegions = chroms.stream().map(rrr::forSequence).collect(Collectors.toList());
      } else {
        mRegions = rr.sequenceNames().stream().map(rr::forSequence).collect(Collectors.toList());
      }
      populateNext();
    }

    @Override
    public void close() throws IOException {
      for (final VcfReader reader : mReaders) {
        if (reader != null) {
          reader.close();
        }
      }
    }


    public VcfHeader getHeader() {
      return mMergedHeader;
    }

    private void populateNext() throws IOException {
      boolean recordActive = false;
      while (!recordActive && mCurrentRegion < mRegions.size()) {
        int minPos = Integer.MAX_VALUE;

        for (int i = 0; i < mReaders.length; ++i) {
          if (mReaders[i] == null) {
            mReaders[i] = new VcfReader(new VcfParser(), new TabixLineReader(mFiles[i], mIndexes[i], mRegions.get(mCurrentRegion)), mHeaders[i]);
          }
          if (mReaders[i].hasNext()) {
            final int pos = mReaders[i].peek().getStart();
            if (pos < minPos) {
              minPos = pos;
              mCurrentRecords.clear();
              mCurrentRecords.add(i);
              recordActive = true;
            } else if (pos == minPos) {
              mCurrentRecords.add(i);
            }
          }
        }
        if (!recordActive) {
          ++mCurrentRegion;
          for (int i = 0; i < mReaders.length; ++i) {
            if (mReaders[i] != null) {
              mReaders[i].close();
            }
            mReaders[i] = null;
          }
        }
      }
    }

    public boolean hasNextPosition() {
      return !mCurrentRecords.isEmpty();
    }

    public void nextPosition(ZipperCallback callback) throws IOException {
      final ArrayList<VcfRecord> recs = new ArrayList<>(mCurrentRecords.size());
      final ArrayList<VcfHeader> headers = new ArrayList<>(mCurrentRecords.size());
      int position;
      for (final int i : mCurrentRecords) {
        do {
          final VcfRecord rec = mReaders[i].next();
          position = rec.getStart();
          recs.add(rec);
          headers.add(mReaders[i].getHeader());
        } while (mReaders[i].hasNext() && mReaders[i].peek().getStart() == position);
      }
      callback.vcfAtPosition(recs.toArray(new VcfRecord[0]), headers.toArray(new VcfHeader[0]));
      mCurrentRecords.clear();
      populateNext();
    }
  }

  interface ZipperCallback {
    void vcfAtPosition(VcfRecord[] records, VcfHeader[] headers) throws IOException;
  }
}
