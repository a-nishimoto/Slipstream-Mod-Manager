package net.vhati.modmanager.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.Properties;

import net.vhati.util.AtomicFileOutput;


/**
 * Records the MD5 of each vanilla .dat backup.
 *
 * Without this, "is this .bak really vanilla?" is answered by File.exists(),
 * which cannot distinguish a genuine vanilla backup from one that was taken of
 * an already-patched dat. That mistake is unrecoverable: once a modded dat has
 * been blessed as the vanilla reference, every later "revert to vanilla"
 * restores modded data, and the only fix is reinstalling FTL.
 *
 * The manifest is written only after a full set of backups has been created, so
 * its presence also marks the backup set as complete.
 */
public class BackupManifest {

	public static final String FILENAME = "backup_hashes.properties";

	private final File manifestFile;
	private final Properties props = new Properties();


	private BackupManifest( File manifestFile ) {
		this.manifestFile = manifestFile;
	}


	/**
	 * Loads the manifest for a backup dir, or an empty one if absent.
	 */
	public static BackupManifest load( File backupDir ) throws IOException {
		BackupManifest result = new BackupManifest( new File( backupDir, FILENAME ) );

		if ( result.manifestFile.exists() ) {
			InputStream in = null;
			try {
				in = new FileInputStream( result.manifestFile );
				result.props.load( in );
			}
			finally {
				try {if ( in != null ) in.close();}
				catch ( IOException e ) {}
			}
		}
		return result;
	}


	public boolean isEmpty() {
		return props.isEmpty();
	}

	/** Returns the recorded MD5 for a dat's backup, or null. */
	public String getHash( String datName ) {
		return props.getProperty( datName );
	}

	public void setHash( String datName, String md5 ) {
		props.setProperty( datName, md5 );
	}

	public void clear() {
		props.clear();
	}


	public void save() throws IOException {
		AtomicFileOutput out = null;
		try {
			out = new AtomicFileOutput( manifestFile );
			Writer writer = out.getWriter( "UTF-8" );
			props.store( writer,
				" MD5 of each vanilla .dat backup, written once a full backup set exists.\n"
				+"# Slipstream verifies backups against these before restoring vanilla.\n"
				+"# Deleting this file makes Slipstream treat the current dats as a new\n"
				+"# vanilla baseline, so only do that with an unmodded FTL install." );
			out.commit();
		}
		finally {
			if ( out != null ) out.close();
		}
	}


	/**
	 * Removes the manifest from disk. Returns false if it existed and could not
	 * be deleted.
	 */
	public boolean delete() {
		props.clear();
		if ( !manifestFile.exists() ) return true;
		return manifestFile.delete();
	}
}
