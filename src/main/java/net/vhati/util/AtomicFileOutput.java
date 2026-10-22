package net.vhati.util;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;


/**
 * Writes a file by staging it alongside the destination and renaming on commit.
 *
 * Writing straight to the destination means an interrupted write -- a crash, a
 * kill, a full disk, a yanked USB drive -- leaves a truncated or empty file
 * where a valid one used to be. That matters here: the destinations include
 * FTL's vanilla .dat backups, which cannot be regenerated once lost, and the
 * downloaded mod catalog, whose destination is a populated file shipped with
 * the app.
 *
 * Typical use:
 *
 *   AtomicFileOutput out = null;
 *   try {
 *     out = new AtomicFileOutput( dstFile );
 *     out.getOutputStream().write( ... );
 *     out.commit();
 *   }
 *   finally {
 *     if ( out != null ) out.close();
 *   }
 *
 * close() is idempotent and discards the staged file unless commit() succeeded,
 * so the finally block is safe whether or not the body threw.
 *
 * Note this guarantees the destination is never seen half-written. It does not
 * make a multi-file update transactional; callers updating several files still
 * have to decide what a partial batch means.
 */
public class AtomicFileOutput implements Closeable {

	private static final int MOVE_RETRIES = 5;
	private static final long MOVE_RETRY_DELAY_MS = 250L;

	private final File dstFile;
	private final File tmpFile;
	private final FileOutputStream stream;

	private Writer writer = null;
	private boolean committed = false;
	private boolean closed = false;
	private boolean moveFailed = false;


	public AtomicFileOutput( File dstFile ) throws IOException {
		this.dstFile = dstFile.getAbsoluteFile();

		File parentDir = this.dstFile.getParentFile();
		if ( parentDir != null && !parentDir.exists() ) parentDir.mkdirs();

		// Staged in the destination's own directory, so the rename cannot cross
		// filesystems (which would make it a non-atomic copy).
		this.tmpFile = File.createTempFile( this.dstFile.getName() +".", ".tmp", parentDir );
		this.stream = new FileOutputStream( tmpFile );
	}


	public OutputStream getOutputStream() {
		return stream;
	}

	/**
	 * Returns a Writer over the staged file, created once and reused.
	 *
	 * commit() flushes this before renaming, so callers do not have to.
	 */
	public Writer getWriter( String charsetName ) throws IOException {
		if ( writer == null ) writer = new OutputStreamWriter( stream, charsetName );
		return writer;
	}

	/**
	 * Flushes, forces to disk, and renames the staged file over the destination.
	 *
	 * If the rename is refused -- characteristically a Windows AV scan on the
	 * just-written file, or the running game still holding the destination --
	 * the move is retried briefly before the exception reaches the caller.
	 * Every byte of the payload is already on disk at that point; it is not
	 * the staged file's fault that the last step is contested.
	 */
	public void commit() throws IOException {
		if ( committed ) throw new IllegalStateException( "Already committed: "+ dstFile );
		if ( closed ) throw new IllegalStateException( "Already closed: "+ dstFile );

		if ( writer != null ) writer.flush();
		stream.flush();

		// Force content to disk BEFORE the rename. Without this, a crash can
		// leave the rename durable but the content not, which is the very
		// outcome staging was meant to prevent.
		stream.getFD().sync();
		stream.close();

		try {
			try {
				moveIntoPlace( true );
			}
			catch ( AtomicMoveNotSupportedException e ) {
				// Some filesystems (notably FAT32 on removable media, which FTL
				// installs do turn up on) cannot rename atomically. A plain
				// replace is still better than writing in place.
				moveIntoPlace( false );
			}
		}
		catch ( IOException e ) {
			// All the work is done and only the rename failed. Mark it so
			// close() spares the staged file instead of discarding finished
			// work -- a delayed cleanup can remove it once the lock is gone.
			moveFailed = true;
			throw e;
		}

		committed = true;
		closed = true;
	}

	private void moveIntoPlace( boolean atomic ) throws IOException {
		int attempts = 0;
		while ( true ) {
			try {
				if ( atomic ) {
					Files.move( tmpFile.toPath(), dstFile.toPath(),
						StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE );
				}
				else {
					Files.move( tmpFile.toPath(), dstFile.toPath(),
						StandardCopyOption.REPLACE_EXISTING );
				}
				return;
			}
			catch ( AccessDeniedException e ) {
				if ( ++attempts >= MOVE_RETRIES ) throw e;
				try {Thread.sleep( MOVE_RETRY_DELAY_MS );}
				catch ( InterruptedException ie ) {
					// A cancel still gets the honest failure, not a false success.
					Thread.currentThread().interrupt();
					throw e;
				}
			}
		}
	}

	/**
	 * Releases the staged file, discarding it unless commit() succeeded.
	 *
	 * A commit whose rename failed leaves the staged file alone: it holds
	 * completed work, and on Windows it typically cannot be deleted while
	 * whatever denied the move still holds it.
	 */
	@Override
	public void close() {
		if ( closed ) return;
		closed = true;

		try {stream.close();}
		catch ( IOException e ) {}

		if ( !committed && !moveFailed && tmpFile.exists() ) tmpFile.delete();
	}
}
