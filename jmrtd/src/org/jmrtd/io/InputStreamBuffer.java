/*
 * JMRTD - A Java API for accessing machine readable travel documents.
 *
 * Copyright (C) 2006 - 2015  The JMRTD team
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 *
 * $Id$
 */

package org.jmrtd.io;

import java.io.IOException;
import java.io.InputStream;

import org.jmrtd.io.FragmentBuffer.Fragment;

/**
 * Buffers an inputstream (whose length is known in advance) and can supply clients with fresh
 * &quot;copies&quot; of that inputstream served from the buffer.
 * 
 * NOTE: the original inputstream should no longer be read from, clients should only read bytes
 * from the sub-inputstreams.
 * 
 * @author Martijn Oostdijk (martijn.oostdijk@gmail.com)
 */
public class InputStreamBuffer {

	private PositionInputStream carrier;
	private FragmentBuffer buffer;

	/**
	 * Creates an input stream buffer.
	 * 
	 * @param inputStream the input stream
	 * @param length the length of the input stream
	 */
	public InputStreamBuffer(InputStream inputStream, int length) {
		this.carrier = new PositionInputStream(inputStream);
		this.carrier.mark(length);
		this.buffer = new FragmentBuffer(length);
	}

	/**
	 * Updates this buffer based on some other buffer.
	 * 
	 * @param other the other buffer
	 */
	public void updateFrom(InputStreamBuffer other) {
		buffer.updateFrom(other.buffer);
	}

	/**
	 * Gets a copy of the input stream positioned at <code>0</code>.
	 *
	 * @return a copy of the input stream
	 */
	public SubInputStream getInputStream() {
		synchronized(carrier) {
			return new SubInputStream(carrier);
		}
	}

	public synchronized int getPosition() {
		return buffer.getPosition();
	}

	public synchronized int getBytesBuffered() {
		return buffer.getBytesBuffered();
	}

	public int getLength() {
		return buffer.getLength();
	}

	public String toString() {
		return "InputStreamBuffer [" + buffer + "]";
	}

	public class SubInputStream extends InputStream { // FIXME set class visibility to package

		/** The position within this inputstream. */
		private int position;
		private int markedPosition;

		private Object syncObject;

		public SubInputStream(Object syncObject) {
			position = 0;
			markedPosition = -1;
			this.syncObject = syncObject;
		}

		public FragmentBuffer getBuffer() {
			return buffer;
		}

		public int read() throws IOException {
			synchronized(syncObject) {
				if (position >= buffer.getLength()) {
					/* FIXME: Is this correct? Isn't buffer capable of growing dynamically? -- MO */
					return -1;
				} else if (buffer.isCoveredByFragment(position)) {
					/* Serve the byte from the buffer */
					return buffer.getBuffer()[position++] & 0xFF;
				} else {
					/* Get it from the carrier */
					if (carrier.markSupported()) {
						syncCarrierPosition();
					}
					try {
						int result = carrier.read();
						if (result < 0) { return -1; }
						buffer.addFragment(position++, (byte)result);
						return result;
					} catch (IOException ioe) {
						/*
						 * Carrier failed to read. Now what?
						 * - We don't update the buffer or position.
						 * - Obviously we also fail to read, with the same exception.
						 */
						throw ioe;
					}
				}
			}
		}

		public int read(byte[] b) throws IOException {
			synchronized(syncObject) {
				return read(b, 0, b.length);
			}
		}

		public int read(byte[] b, int off, int len) throws IOException {
			synchronized(syncObject) {
				if (b == null) {
					throw new NullPointerException();
				} else if (off < 0 || len < 0 || len > b.length - off) {
					throw new IndexOutOfBoundsException();
				} else if (len == 0) {
					return 0;
				}

				if (len > buffer.getLength() - position) {
					len = buffer.getLength() - position;
				}
				
				if (carrier.markSupported()) {
					syncCarrierPosition();
				}
				
				if (position >= buffer.getLength()) {
					/* FIXME: is this correct? See FIXME in read(). */
					return -1;
				}

				Fragment fragment = buffer.getSmallestUnbufferedFragment(position, len);
				if (fragment.getLength() > 0) {
					/* Copy buffered prefix to b. */
					int alreadyBufferedPrefixLength = fragment.getOffset() - position;
					System.arraycopy(buffer.getBuffer(), position, b, off, alreadyBufferedPrefixLength);
					position += alreadyBufferedPrefixLength;

					/* Read unbuffered fragment from carrier, directly to b. */
					int bytesReadFromCarrier = carrier.read(b, off + alreadyBufferedPrefixLength, fragment.getLength());
					buffer.addFragment(fragment.getOffset(), b, off, bytesReadFromCarrier);
					position += bytesReadFromCarrier;

					return alreadyBufferedPrefixLength + bytesReadFromCarrier;					
				} else {
					int length = Math.min(len, buffer.getLength() - position);
					System.arraycopy(buffer.getBuffer(), position, b, off, length);
					position += length;
					return length;
				}
			}
		}

		public long skip(long n) throws IOException {
			synchronized(syncObject) {
				int leftInBuffer = buffer.getBufferedLength(position);

				if (n <= leftInBuffer) {
					/* If we can skip within the buffer, we do */
					position += n;
					return n;
				} else {
					assert(leftInBuffer < n);
					/* Otherwise, skip what's left in buffer, then skip within carrier... */
					position += leftInBuffer;
					long skippedBytes = 0;
					if (carrier.markSupported()) {
						syncCarrierPosition();
						skippedBytes = carrier.skip(n - leftInBuffer);
						position += (int)skippedBytes;
					} else {
						skippedBytes = super.skip(n - leftInBuffer);
						/* As super.skip will call read, position will be adjusted automatically. */
					}
					return leftInBuffer + skippedBytes;
				}
			}
		}

		public int available() throws IOException {
			return buffer.getBufferedLength(position);
		}

		public void close() throws IOException {
		}

		public synchronized void mark(int readLimit) {
			markedPosition = position;
		}

		public synchronized void reset() throws IOException {
			if (markedPosition < 0) { throw new IOException("Invalid reset, was mark() called?"); }
			position = markedPosition;
		}

		public boolean markSupported() {
			return true;
		}

		public int getPosition() {
			return position;
		}

		/**
		 * If necessary, resets the carrier (which must support mark) and
		 * skips to the current position in the buffer.
		 * 
		 * @throws IOException on error
		 */
		private void syncCarrierPosition() throws IOException {
			if (position == carrier.getPosition()) {
				return;
			}
			carrier.reset();
			int bytesSkipped = 0;
			while (bytesSkipped < position) {
				bytesSkipped += carrier.skip(position - bytesSkipped);
			}
		}
	}
}
