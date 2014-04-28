package au.edu.wehi.socrates;

import java.util.PriorityQueue;

import net.sf.samtools.SAMRecord;
import net.sf.samtools.SAMSequenceDictionary;

import org.broadinstitute.variant.variantcontext.VariantContext;

import com.google.common.collect.AbstractIterator;
import com.google.common.collect.PeekingIterator;

/**
 * Iterates through evidence for a breakpoint at 
 * @author Daniel Cameron
 *
 */
public class DirectedEvidenceIterator extends AbstractIterator<DirectedEvidence> {
	private static final int INITIAL_BUFFER_SIZE = 1024;
	private final SAMSequenceDictionary dictionary;
	private final PeekingIterator<SAMRecord> svIterator;
	private final PeekingIterator<VariantContext> vcfIterator;
	private final LinearGenomicCoordinate linear;
	private final SequentialNonReferenceReadPairFactory mateFactory;
	private final SequentialRealignedBreakpointFactory realignFactory;
	private final PriorityQueue<DirectedEvidence> calls = new PriorityQueue<DirectedEvidence>(INITIAL_BUFFER_SIZE, new DirectedEvidenceCoordinateIntervalComparator());
	private final int maxFragmentSize;
	private long currentLinearPosition = -1;
	/**
	 * Iterates over breakpoint evidence
	 * @param sv structural variation supporting reads. Must be sorted by coordinate
	 * @param mate mate reads of structural variation supporting reads. Must be sorted by mate coordinate
	 * @param realign breakpoint realignments. Must be sorted by source @see DirectedBreakpoint start coordinate 
	 * @param vcf Assembly-based @see DirectedBreakpoint. Must be sorted by coordinate
	 */
	public DirectedEvidenceIterator(
			PeekingIterator<SAMRecord> sv,
			PeekingIterator<SAMRecord> mate,
			PeekingIterator<SAMRecord> realign,
			PeekingIterator<VariantContext> vcf,
			SAMSequenceDictionary dictionary,
			int maxFragmentSize) {
		this.linear = new LinearGenomicCoordinate(dictionary);
		this.dictionary = dictionary;
		this.svIterator = sv;
		this.vcfIterator = vcf;
		this.mateFactory = new SequentialNonReferenceReadPairFactory(mate);
		this.realignFactory = new SequentialRealignedBreakpointFactory(realign);
		this.maxFragmentSize = maxFragmentSize;
	}
	@Override
	protected DirectedEvidence computeNext() {
		do {
			if (!calls.isEmpty() && linear.getStartLinearCoordinate(calls.peek().getBreakpointLocation()) < currentLinearPosition - maxFragmentSize) {
				return fixCall(calls.poll());
			}
		} while (advance());
		// no more input: flush our buffer
		while (!calls.isEmpty()) return fixCall(calls.poll());
		return endOfData();
	}
	/**
	 * Need to match realigned record at output time since input BAM processing is in order of alignment start,
	 * not putative breakpoint position
	 * @param evidence call to match realignment record for
	 * @return call including realignment mapping evidence if applicable
	 */
	private DirectedEvidence fixCall(DirectedEvidence evidence) {
		//  
		if (evidence instanceof SoftClipEvidence) {
			evidence = new SoftClipEvidence((SoftClipEvidence)evidence, realignFactory.findRealignedSAMRecord((DirectedBreakpoint)evidence));
		} else if (evidence instanceof DirectedBreakpointAssembly) {
			evidence = DirectedBreakpointAssembly.create((DirectedBreakpointAssembly)evidence, realignFactory.findRealignedSAMRecord((DirectedBreakpoint)evidence));
		}
		return evidence;
	}
	/**
	 * Advances the input streams to the next
	 * @return true if records were consumed, false if no more input exists
	 */
	private boolean advance() {
		long nextPos = Long.MAX_VALUE;
		if (svIterator != null && svIterator.hasNext()) {
			nextPos = Math.min(nextPos, linear.getLinearCoordinate(svIterator.peek().getReferenceIndex(), svIterator.peek().getAlignmentStart()));
		}
		if (vcfIterator != null && vcfIterator.hasNext()) {
			nextPos = Math.min(nextPos, linear.getLinearCoordinate(vcfIterator.peek().getChr(), vcfIterator.peek().getStart()));
		}
		if (nextPos == Long.MAX_VALUE) return false; // no records
		while (svIterator != null && svIterator.hasNext() && linear.getLinearCoordinate(svIterator.peek().getReferenceIndex(), svIterator.peek().getAlignmentStart()) == nextPos) {
			processRead(svIterator.next());
		}
		while (vcfIterator != null && vcfIterator.hasNext() && linear.getLinearCoordinate(vcfIterator.peek().getChr(), vcfIterator.peek().getStart()) == nextPos) {
			processVariant(vcfIterator.next());
		}
		currentLinearPosition = nextPos;
		return true;
	}
	private void processVariant(VariantContext variant) {
		SocratesVariantContext managedContext = SocratesVariantContext.create(dictionary, variant);
		if (managedContext instanceof DirectedEvidence) {
			calls.add((DirectedEvidence)managedContext);
		}
	}
	private void processRead(SAMRecord record) {
		if (SAMRecordUtil.getStartSoftClipLength(record) > 0) {
			calls.add(new SoftClipEvidence(BreakpointDirection.Backward, record));
		}
		if (SAMRecordUtil.getEndSoftClipLength(record) > 0) {
			calls.add(new SoftClipEvidence(BreakpointDirection.Forward, record));
		}
		if (SAMRecordUtil.isPartOfNonReferenceReadPair(record)) {
			NonReferenceReadPair pair = mateFactory.createNonReferenceReadPair(record, maxFragmentSize);
			if (pair != null) {
				calls.add(pair);
			}
		}
	}
}
