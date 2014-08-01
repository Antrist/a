package au.edu.wehi.idsv;

import htsjdk.samtools.BAMRecordCodec;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMFileHeader.SortOrder;
import htsjdk.samtools.SAMFileWriter;
import htsjdk.samtools.SAMRecord;
import htsjdk.samtools.SAMRecordIterator;
import htsjdk.samtools.SAMSequenceDictionary;
import htsjdk.samtools.SAMSequenceRecord;
import htsjdk.samtools.SamReader;
import htsjdk.samtools.metrics.MetricsFile;
import htsjdk.samtools.reference.ReferenceSequenceFileWalker;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.Log;
import htsjdk.samtools.util.ProgressLogger;
import htsjdk.samtools.util.SortingCollection;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.List;

import picard.analysis.InsertSizeMetrics;
import picard.analysis.directed.InsertSizeMetricsCollector;
import au.edu.wehi.idsv.metrics.IdsvMetrics;
import au.edu.wehi.idsv.metrics.IdsvMetricsCollector;
import au.edu.wehi.idsv.metrics.IdsvSamFileMetrics;
import au.edu.wehi.idsv.metrics.RelevantMetrics;

import com.google.common.collect.Lists;

/**
 * Structural variation evidence based on read pairs from a single SAM/BAM.  
 * @author cameron.d
 *
 */
public class SAMEvidenceSource extends EvidenceSource {
	private static final Log log = Log.getInstance(SAMEvidenceSource.class);
	public static final String FLAG_NAME = "extracted";
	private final ProcessingContext processContext;
	private final File input;
	private final boolean isTumour;
	private RelevantMetrics metrics;
	public SAMEvidenceSource(ProcessingContext processContext, File file, boolean isTumour) {
		super(processContext, file);
		this.processContext = processContext;
		this.input = file;
		this.isTumour = isTumour;
	}
	/**
	 * Ensures that all structural variation evidence has been extracted from the input file 
	 * @return returns whether any processing was performed
	 */
	public boolean ensureEvidenceExtracted() {
		if (!isProcessingComplete()) {
			process();
			return true;
		} else {
			return false;
		}
	}
	@Override
	protected void process() {
		ExtractEvidence extract = null;
		try {
			extract = new ExtractEvidence();
			log.info("START extract evidence for ", input);
			extract.extractEvidence();
			log.info("SUCCESS extract evidence for ", input);
		}
		catch (Exception e) {
			log.info("FAILURE extract evidence for ", input);
			log.error(e);
		} finally {
			if (extract != null) extract.close();
		}
	}
	@Override
	protected boolean isProcessingComplete() {
		boolean done = true;
		FileSystemContext fsc = processContext.getFileSystemContext();
		done &= IntermediateFileUtil.checkIntermediate(fsc.getInsertSizeMetrics(input), input);
		done &= IntermediateFileUtil.checkIntermediate(fsc.getIdsvMetrics(input), input);
		if (processContext.shouldProcessPerChromosome()) {
			for (SAMSequenceRecord seq : processContext.getReference().getSequenceDictionary().getSequences()) {
				done &= IntermediateFileUtil.checkIntermediate(fsc.getSVBamForChr(input, seq.getSequenceName()), input);
				done &= IntermediateFileUtil.checkIntermediate(fsc.getMateBamForChr(input, seq.getSequenceName()), input);
				done &= IntermediateFileUtil.checkIntermediate(fsc.getRealignmentFastqForChr(input, seq.getSequenceName()), input);
			}
		} else {
			done &= IntermediateFileUtil.checkIntermediate(fsc.getSVBam(input), input);
			done &= IntermediateFileUtil.checkIntermediate(fsc.getMateBam(input), input);
			done &= IntermediateFileUtil.checkIntermediate(fsc.getRealignmentFastq(input), input);
		}
		done &= IntermediateFileUtil.checkIntermediate(fsc.getFlagFile(input, FLAG_NAME), input);
		return done;
	}
	public RelevantMetrics getMetrics() {
		if (metrics == null) {
			ensureEvidenceExtracted();
			metrics = new IdsvSamFileMetrics(processContext.getFileSystemContext().getInsertSizeMetrics(input), processContext.getFileSystemContext().getIdsvMetrics(input));
		}
		return metrics;
	}
	public File getSourceFile() { return input; }
	public boolean isTumour() { return isTumour; }
	@Override
	protected DirectedEvidenceFileIterator perChrIterator(String chr) {
		FileSystemContext fsc = processContext.getFileSystemContext();
		return new DirectedEvidenceFileIterator(processContext, this,
				fsc.getSVBamForChr(input, chr),
				fsc.getMateBamForChr(input, chr),
				isRealignmentComplete() ? fsc.getRealignmentBamForChr(input, chr) : null,
				null);
	}
	@Override
	protected DirectedEvidenceFileIterator singleFileIterator() {
		FileSystemContext fsc = processContext.getFileSystemContext();
		return new DirectedEvidenceFileIterator(processContext, this,
				fsc.getSVBam(input),
				fsc.getMateBam(input),
				isRealignmentComplete() ? fsc.getRealignmentBam(input) : null,
				null);
	}
	/**
	 * Extracts reads supporting structural variation into intermediate files.
	 * By sorting DP and OEA read pairs by the coordinate of the mapped mate,
	 * putative directed breakpoints can be assembled by downstream processes
	 * in a single pass of the intermediate files.
	 * 
	 * @author Daniel Cameron
	 *
	 */
	private class ExtractEvidence implements Closeable {
		private SamReader reader = null;
		private ReferenceSequenceFileWalker referenceWalker = null;
		private final List<SAMFileWriter> writers = Lists.newArrayList();
		private final List<SAMFileWriter> matewriters = Lists.newArrayList();
		//private final List<SortingCollection<SAMRecord>> mateRecordBuffer = Lists.newArrayList();
		private final List<FastqBreakpointWriter> realignmentWriters = Lists.newArrayList();
		public void close() {
			tryClose(reader);
			reader = null;
			tryClose(referenceWalker);
			referenceWalker = null;
			for (SAMFileWriter w : writers) {
				tryClose(w);
			}
			writers.clear();
	    	for (SAMFileWriter w : matewriters) {
				tryClose(w);
			}
	    	matewriters.clear();
	    	for (FastqBreakpointWriter w : realignmentWriters) {
				tryClose(w);
			}
	    	realignmentWriters.clear();
		}
		private void tryClose(Closeable toClose) {
			try {
				if (toClose != null) toClose.close();
	    	} catch (IOException e) {
	    		log.debug(e);
	    	}
		}
		/**
		 * Deletes all output files
		 * Deleting output files if there is an error prevents downstream issues
		 * with partially written intermediate files 
		 */
		private void deleteOutput() {
			close(); // close any file handles that are still around
			FileSystemContext fsc = processContext.getFileSystemContext();
			tryDelete(fsc.getFlagFile(input, FLAG_NAME));
			tryDelete(fsc.getInsertSizeMetrics(input));
			tryDelete(fsc.getIdsvMetrics(input));
			tryDelete(fsc.getSVBam(input));
			tryDelete(fsc.getMateBam(input));
			tryDelete(fsc.getRealignmentFastq(input));
			for (SAMSequenceRecord seqr : processContext.getReference().getSequenceDictionary().getSequences()) {
				String seq = seqr.getSequenceName();
				tryDelete(fsc.getSVBamForChr(input, seq));
				tryDelete(fsc.getMateBamForChr(input, seq));
				tryDelete(fsc.getRealignmentFastqForChr(input, seq));
			}
		}
		private void tryDelete(File f) {
			try {
				if (f.exists()) {
					if (!f.delete()) {
						log.error("Unable to delete intermediate file ", f,  " during rollback.");
					}
				}
			} catch (Exception e) {
				log.error(e, "Unable to delete intermediate file ", f,  " during rollback.");
			}
		}
		public void extractEvidence() {
			boolean shouldDelete = true;
	    	try {
	    		deleteOutput(); // trash any left-over files
	    				    	
		    	reader = processContext.getSamReaderFactory().open(input);
		    	final SAMFileHeader header = reader.getFileHeader();
		    	final SAMSequenceDictionary dictionary = header.getSequenceDictionary();
		    	referenceWalker = new ReferenceSequenceFileWalker(processContext.getReferenceFile());
		    	final InsertSizeMetricsCollector ismc = IdsvSamFileMetrics.createInsertSizeMetricsCollector(header);
		    	final IdsvMetricsCollector imc = IdsvSamFileMetrics.createIdsvMetricsCollector();
		    	
		    	dictionary.assertSameDictionary(processContext.getReference().getSequenceDictionary());
		    	
		    	createOutputWriters(header, dictionary);
		    	
		    	processInputRecords(dictionary, ismc, imc);
		    	
		    	flush();
		    	
		    	writeMetrics(ismc, imc);
		    	
		    	sortMates();
		    	
		    	if (!processContext.getFileSystemContext().getFlagFile(input, FLAG_NAME).createNewFile()) {
		    		log.error("Failed to create flag file ", processContext.getFileSystemContext().getFlagFile(input, FLAG_NAME));
		    	} else {
		    		shouldDelete = false;
		    	}
	    	} catch (IOException e) {
	    		log.error(e);
				e.printStackTrace();
				shouldDelete = true;
				throw new RuntimeException(e);
			} finally {
	    		close();
	    		if (shouldDelete) deleteOutput();
	    		// Remove temp files
	    		tryDelete(processContext.getFileSystemContext().getMateBamUnsorted(input));
				for (SAMSequenceRecord seqr : processContext.getReference().getSequenceDictionary().getSequences()) {
					String seq = seqr.getSequenceName();
					tryDelete(processContext.getFileSystemContext().getMateBamUnsortedForChr(input, seq));
				}
	    	}
	    }
		private void sortMates() {
			log.info("Sorting sv mates");
			if (processContext.shouldProcessPerChromosome()) {
				for (SAMSequenceRecord seqr : processContext.getReference().getSequenceDictionary().getSequences()) {
					String seq = seqr.getSequenceName();
					sortByMateCoordinate(
							processContext.getFileSystemContext().getMateBamUnsortedForChr(input, seq),
							processContext.getFileSystemContext().getMateBamForChr(input, seq));
				}
			} else {
				sortByMateCoordinate(
						processContext.getFileSystemContext().getMateBamUnsorted(input),
						processContext.getFileSystemContext().getMateBam(input));
			}
		}
		/**
		 * Sorts records in the given SAM/BAM file by the mate coordinate of each read 
		 * @param unsorted
		 * @param output
		 */
		private void sortByMateCoordinate(File unsorted, File output) {
			log.info("Sorting " + output);
			SamReader reader = null;
			CloseableIterator<SAMRecord> rit = null;
			SAMFileWriter writer = null;
			CloseableIterator<SAMRecord> wit = null;
			try {
				reader = processContext.getSamReaderFactory().open(unsorted);
				SAMFileHeader header = reader.getFileHeader();
				rit = reader.iterator();
				SortingCollection<SAMRecord> collection = SortingCollection.newInstance(
						SAMRecord.class,
						new BAMRecordCodec(header),
						new SAMRecordMateCoordinateComparator(),
						processContext.getFileSystemContext().getMaxBufferedRecordsPerFile(),
						processContext.getFileSystemContext().getTemporaryDirectory());
				while (rit.hasNext()) {
					collection.add(rit.next());
				}
				collection.doneAdding();
				writer = processContext.getSamReaderWriterFactory().makeSAMOrBAMWriter(header, true, output);
				writer.setProgressLogger(new ProgressLogger(log));
		    	wit = collection.iterator();
				while (wit.hasNext()) {
					writer.addAlignment(wit.next());
				}
			} finally {
				if (writer != null) writer.close();
				if (wit != null) wit.close();
				if (rit != null) rit.close();
				try {
					if (reader != null) reader.close();
				} catch (IOException e) { }
			}
		}
		private void writeMetrics(InsertSizeMetricsCollector ismc, IdsvMetricsCollector imc) {
			log.info("Writing metrics");
			
			ismc.finish();
			MetricsFile<InsertSizeMetrics, Integer> ismmf = processContext.<InsertSizeMetrics, Integer>createMetricsFile();
			ismc.addAllLevelsToFile(ismmf);
			ismmf.write(processContext.getFileSystemContext().getInsertSizeMetrics(input));
			
			imc.finish();
			MetricsFile<IdsvMetrics, Integer> imcf = processContext.<IdsvMetrics, Integer>createMetricsFile();
			imc.addAllLevelsToFile(imcf);
			imcf.write(processContext.getFileSystemContext().getIdsvMetrics(input));
		}
		private void flush() throws IOException {
			reader.close();
			reader = null;
			for (SAMFileWriter w : writers) {
				w.close();
			}
			writers.clear();
			for (SAMFileWriter w : matewriters) {
				w.close();
			}
			matewriters.clear();
			for (FastqBreakpointWriter w : realignmentWriters) {
				w.close();
			}
			realignmentWriters.clear();
		}
		private void processInputRecords(final SAMSequenceDictionary dictionary, final InsertSizeMetricsCollector ismc, final IdsvMetricsCollector imc) {
			// output all to a single file, or one per chr + one for unmapped 
			assert(writers.size() == dictionary.getSequences().size() + 1 || writers.size() == 1);
			assert(matewriters.size() == dictionary.getSequences().size() || matewriters.size() == 1);
			assert(realignmentWriters.size() == dictionary.getSequences().size() || realignmentWriters.size() == 1);
			
			// Traverse the input file
			final ProgressLogger progress = new ProgressLogger(log);
			SAMRecordIterator rawIterator = null;
			try {
				rawIterator = reader.iterator();
				final CloseableIterator<SAMRecord> iter = processContext.applyCommonSAMRecordFilters(rawIterator.assertSorted(SortOrder.coordinate));
				while (iter.hasNext()) {
					SAMRecord record = iter.next();
					SAMRecordUtil.ensureNmTag(referenceWalker, record);
					int offset = record.getReadUnmappedFlag() ? dictionary.size() : record.getReferenceIndex();
					SoftClipEvidence startEvidence = null;
					SoftClipEvidence endEvidence = null;
					if (SAMRecordUtil.getStartSoftClipLength(record) > 0) {
						startEvidence = SoftClipEvidence.create(processContext, SAMEvidenceSource.this, BreakendDirection.Backward, record);
						if (processContext.getSoftClipParameters().meetsEvidenceCritera(startEvidence)) {
							if (processContext.getRealignmentParameters().shouldRealignBreakend(startEvidence)) {
								realignmentWriters.get(offset % realignmentWriters.size()).write(startEvidence);
							}
						} else {
							startEvidence = null;
						}
					}
					if (SAMRecordUtil.getEndSoftClipLength(record) > 0) {
						endEvidence = SoftClipEvidence.create(processContext, SAMEvidenceSource.this, BreakendDirection.Forward, record);
						if (processContext.getSoftClipParameters().meetsEvidenceCritera(endEvidence)) {
							if (processContext.getRealignmentParameters().shouldRealignBreakend(endEvidence)) {
								realignmentWriters.get(offset % realignmentWriters.size()).write(endEvidence);
							}
						} else {
							endEvidence = null;
						}
					}				
					boolean badpair = SAMRecordUtil.isPartOfNonReferenceReadPair(record);
					if (startEvidence != null || endEvidence != null || badpair) {
						writers.get(offset % writers.size()).addAlignment(record);
					}
					if (badpair) {
						if (!record.getMateUnmappedFlag()) {
							matewriters.get(record.getMateReferenceIndex() % matewriters.size()).addAlignment(record);
						}
					}
					ismc.acceptRecord(record, null);
					imc.acceptRecord(record, null);
					progress.record(record);
				}
			} finally {
				if (rawIterator != null) rawIterator.close();
			}
		}
		private void createOutputWriters(final SAMFileHeader header, final SAMSequenceDictionary dictionary) {
			final SAMFileHeader svHeader = header.clone();
			svHeader.setSortOrder(SortOrder.coordinate);
			final SAMFileHeader mateHeader = header.clone();
			mateHeader.setSortOrder(SortOrder.unsorted);
			if (processContext.shouldProcessPerChromosome()) {
				createOutputWritersPerChromosome(dictionary, svHeader, mateHeader);
			} else {
				createOutputWriterPerGenome(svHeader, mateHeader);
			}
		}
		private void createOutputWriterPerGenome(final SAMFileHeader svHeader, final SAMFileHeader mateHeader) {
			// all writers map to the same one
			writers.add(processContext.getSamReaderWriterFactory().makeSAMOrBAMWriter(svHeader, true, processContext.getFileSystemContext().getSVBam(input)));
			matewriters.add(processContext.getSamReaderWriterFactory().makeBAMWriter(mateHeader, true, processContext.getFileSystemContext().getMateBamUnsorted(input), 0));
			realignmentWriters.add(new FastqBreakpointWriter(processContext.getFastqWriterFactory().newWriter(processContext.getFileSystemContext().getRealignmentFastq(input))));
		}
		private void createOutputWritersPerChromosome(
				final SAMSequenceDictionary dictionary,
				final SAMFileHeader svHeader, final SAMFileHeader mateHeader) {
			for (SAMSequenceRecord seq : dictionary.getSequences()) {
				writers.add(processContext.getSamReaderWriterFactory().makeSAMOrBAMWriter(svHeader, true, processContext.getFileSystemContext().getSVBamForChr(input, seq.getSequenceName())));
				matewriters.add(processContext.getSamReaderWriterFactory().makeBAMWriter(mateHeader, true, processContext.getFileSystemContext().getMateBamUnsortedForChr(input, seq.getSequenceName()), 0));
				realignmentWriters.add(new FastqBreakpointWriter(processContext.getFastqWriterFactory().newWriter(processContext.getFileSystemContext().getRealignmentFastqForChr(input, seq.getSequenceName()))));
			}
			writers.add(processContext.getSamReaderWriterFactory().makeSAMOrBAMWriter(svHeader, true, processContext.getFileSystemContext().getSVBamForChr(input, "unmapped")));
		}
	}
}