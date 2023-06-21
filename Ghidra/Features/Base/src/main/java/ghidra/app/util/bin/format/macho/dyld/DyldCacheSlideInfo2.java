/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.util.bin.format.macho.dyld;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import ghidra.app.util.bin.BinaryReader;
import ghidra.app.util.bin.format.macho.MachConstants;
import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.task.TaskMonitor;

/**
 * Represents a dyld_cache_slide_info2 structure.
 * 
 * @see <a href="https://github.com/apple-oss-distributions/dyld/blob/main/cache-builder/dyld_cache_format.h">dyld_cache_format.h</a> 
 */
public class DyldCacheSlideInfo2 extends DyldCacheSlideInfoCommon {

	private static final int DYLD_CACHE_SLIDE_PAGE_ATTR_NO_REBASE = 0x4000;
	private static final int DYLD_CACHE_SLIDE_PAGE_ATTR_EXTRA = 0x8000;

	private int page_size;
	private int page_starts_offset;
	private int page_starts_count;
	private int page_extras_offset;
	private int page_extras_count;
	private long delta_mask;
	private long value_add;
	private short page_starts_entries[];
	private short page_extras_entries[];

	public long getPageSize() {
		return Integer.toUnsignedLong(page_size);
	}

	public long getPageStartsOffset() {
		return Integer.toUnsignedLong(page_starts_offset);
	}

	public long getPageStartsCount() {
		return Integer.toUnsignedLong(page_starts_count);
	}

	public long getPageExtrasOffset() {
		return Integer.toUnsignedLong(page_extras_offset);
	}

	public long getPageExtrasCount() {
		return Integer.toUnsignedLong(page_extras_count);
	}

	public long getDeltaMask() {
		return delta_mask;
	}

	public long getValueAdd() {
		return value_add;
	}

	public short[] getPageStarts() {
		return page_starts_entries;
	}

	public short[] getPageExtras() {
		return page_extras_entries;
	}

	/**
	 * Create a new {@link DyldCacheSlideInfo2}.
	 * 
	 * @param reader A {@link BinaryReader} positioned at the start of a DYLD slide info 2
	 * @throws IOException if there was an IO-related problem creating the DYLD slide info 2
	 */
	public DyldCacheSlideInfo2(BinaryReader reader) throws IOException {
		super(reader);
		page_size = reader.readNextInt();
		page_starts_offset = reader.readNextInt();
		page_starts_count = reader.readNextInt();
		page_extras_offset = reader.readNextInt();
		page_extras_count = reader.readNextInt();
		delta_mask = reader.readNextLong();
		value_add = reader.readNextLong();
		page_starts_entries = reader.readNextShortArray(page_starts_count);
		page_extras_entries = reader.readNextShortArray(page_extras_count);
	}

	@Override
	public DataType toDataType() throws DuplicateNameException, IOException {
		StructureDataType struct = new StructureDataType("dyld_cache_slide_info2", 0);
		struct.add(DWORD, "version", "");
		struct.add(DWORD, "page_size", "");
		struct.add(DWORD, "page_starts_offset", "");
		struct.add(DWORD, "page_starts_count", "");
		struct.add(DWORD, "page_extras_offset", "");
		struct.add(DWORD, "page_extras_count", "");
		struct.add(QWORD, "delta_mask", "");
		struct.add(QWORD, "value_add", "");
		struct.setCategoryPath(new CategoryPath(MachConstants.DATA_TYPE_CATEGORY));
		return struct;
	}

	@Override
	public void fixPageChains(Program program, DyldCacheHeader dyldCacheHeader,
			boolean addRelocations, MessageLog log, TaskMonitor monitor)
			throws MemoryAccessException, CancelledException {

		long fixedAddressCount = 0;

		List<DyldCacheMappingInfo> mappingInfos = dyldCacheHeader.getMappingInfos();
		DyldCacheMappingInfo dyldCacheMappingInfo = mappingInfos.get(DATA_PAGE_MAP_ENTRY);
		long dataPageStart = dyldCacheMappingInfo.getAddress();
		long pageSize = getPageSize();
		long pageStartsCount = getPageStartsCount();

		long deltaMask = getDeltaMask();
		long deltaShift = Long.numberOfTrailingZeros(deltaMask);
		long valueAdd = getValueAdd();

		short[] pageEntries = getPageStarts();
		short[] extraEntries = getPageExtras();

		monitor.setMessage("Fixing V2 chained data page pointers...");

		monitor.setMaximum(pageStartsCount);
		for (int index = 0; index < pageStartsCount; index++) {
			monitor.checkCancelled();

			long page = dataPageStart + (pageSize * index);

			monitor.setProgress(index);

			int pageEntry = pageEntries[index] & 0xffff;
			if (pageEntry == DYLD_CACHE_SLIDE_PAGE_ATTR_NO_REBASE) {
				continue;
			}

			List<Address> unchainedLocList;

			if ((pageEntry & DYLD_CACHE_SLIDE_PAGE_ATTR_EXTRA) != 0) {
				// go into extras and process list of chain entries for the same page
				int extraIndex = (pageEntry & CHAIN_OFFSET_MASK);
				unchainedLocList = new ArrayList<Address>(1024);
				do {
					pageEntry = extraEntries[extraIndex] & 0xffff;
					long pageOffset = (pageEntry & CHAIN_OFFSET_MASK) * BYTES_PER_CHAIN_OFFSET;

					List<Address> subLocList;
					subLocList = processPointerChain2(program, page, pageOffset, deltaMask,
						deltaShift, valueAdd, addRelocations, monitor);
					unchainedLocList.addAll(subLocList);
					extraIndex++;
				}
				while ((pageEntry & DYLD_CACHE_SLIDE_PAGE_ATTR_EXTRA) == 0);
			}
			else {
				long pageOffset = pageEntry * BYTES_PER_CHAIN_OFFSET;

				unchainedLocList = processPointerChain2(program, page, pageOffset, deltaMask,
					deltaShift, valueAdd, addRelocations, monitor);
			}

			fixedAddressCount += unchainedLocList.size();

			createChainPointers(program, unchainedLocList, monitor);
		}

		log.appendMsg("Fixed " + fixedAddressCount + " chained pointers.");
	}

	/**
	 * Fixes up any chained pointers, starting at the given address.
	 * 
	 * @param program the program
	 * @param page within data pages that has pointers to be unchained
	 * @param nextOff offset within the page that is the chain start
	 * @param deltaMask delta offset mask for each value
	 * @param deltaShift shift needed for the deltaMask to extract the next offset
	 * @param valueAdd value to be added to each chain pointer
	 * 
	 * @throws MemoryAccessException IO problem reading file
	 * @throws CancelledException user cancels
	 */
	private List<Address> processPointerChain2(Program program, long page, long nextOff,
			long deltaMask, long deltaShift, long valueAdd, boolean addRelocations,
			TaskMonitor monitor) throws MemoryAccessException, CancelledException {

		// TODO: should the image base be used to perform the ASLR slide on the pointers.
		//        currently image is kept at it's initial location with no ASLR.
		Address chainStart = program.getLanguage().getDefaultSpace().getAddress(page);
		Memory memory = program.getMemory();
		List<Address> unchainedLocList = new ArrayList<>(1024);

		long valueMask = 0xffffffffffffffffL >>> (64 - deltaShift);

		long delta = -1;
		while (delta != 0) {
			monitor.checkCancelled();

			Address chainLoc = chainStart.add(nextOff);
			long chainValue = memory.getLong(chainLoc);

			delta = (chainValue & deltaMask) >> deltaShift;
			chainValue = chainValue & valueMask;
			if (chainValue != 0) {
				chainValue += valueAdd;
				// chainValue += slideAmount - if we were sliding
			}
			if (addRelocations) {
				addRelocationTableEntry(program, chainLoc, 2, chainValue, 8, null);
			}

			memory.setLong(chainLoc, chainValue);

			// delay creating data until after memory has been changed
			unchainedLocList.add(chainLoc);

			nextOff += (delta * 4);
		}

		return unchainedLocList;
	}
}