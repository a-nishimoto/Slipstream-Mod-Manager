package net.vhati.modmanager.core;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.jdom2.JDOMException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.vhati.ftldat.AbstractPack;
import net.vhati.ftldat.AbstractPack.RepackResult;
import net.vhati.ftldat.FTLPack;
import net.vhati.ftldat.PkgPack;
import net.vhati.ftldat.PackContainer;
import net.vhati.ftldat.PackUtilities;
import net.vhati.modmanager.core.ModPatchObserver;
import net.vhati.modmanager.core.ModUtilities;


public class ModPatchThread extends Thread {

	private static final Logger log = LoggerFactory.getLogger( ModPatchThread.class );

	// Other threads can check or set this.
	public volatile boolean keepRunning = true;

	private Thread shutdownHook = null;

	private List<File> modFiles = new ArrayList<File>();
	private File datsDir = null;
	private File backupDir = null;
	private boolean globalPanic = false;
	private ModPatchObserver observer = null;

	private final int progMax = 100;
	private final int progBackupMax = 25;
	private final int progClobberMax = 25;
	private final int progModsMax = 40;
	private final int progRepackMax = 5;
	private int progMilestone = 0;


	public ModPatchThread( List<File> modFiles, File datsDir, File backupDir, boolean globalPanic, ModPatchObserver observer ) {
		this.modFiles.addAll( modFiles );
		this.datsDir = datsDir;
		this.backupDir = backupDir;
		this.globalPanic = globalPanic;
		this.observer = observer;
	}


	public void run() {
		boolean result;
		Exception exception = null;

		// When JVM tries to exit, stall until this thread ends on its own.
		shutdownHook = new Thread() {
			@Override
			public void run() {
				keepRunning = false;
				boolean interrupted = false;
				try {
					while ( ModPatchThread.this.isAlive() ) {
						try {
							ModPatchThread.this.join();
						}
						catch ( InterruptedException e ) {
							interrupted = true;
						}
					}
				}
				finally {
					if ( interrupted ) Thread.currentThread().interrupt();
				}
			}
		};
		Runtime.getRuntime().addShutdownHook( shutdownHook );

		try {
			result = patch();
		}
		catch ( Exception e ) {
			log.error( "Patching failed.", e );
			exception = e;
			result = false;
		}

		observer.patchingEnded( result, exception );

		Runtime.getRuntime().removeShutdownHook( shutdownHook );
	}


	/**
	 * Logs a non-fatal problem and reports it to the observer.
	 *
	 * Warnings used to be log-only, and modman-log.txt is truncated on every
	 * launch, so the conditions that most often explain "my mod did nothing"
	 * never reached the person who needed them.
	 */
	private void warn( String message ) {
		log.warn( message );
		observer.patchingWarning( message );
	}


	private boolean patch() throws IOException, JDOMException {

		observer.patchingProgress( 0, progMax );

		PackContainer packContainer = null;

		try {
			int modsInstalled = 0;
			int datsRepacked = 0;

			File ftlDatFile = new File( datsDir, "ftl.dat" );
			File dataDatFile = new File( datsDir, "data.dat" );
			File resourceDatFile = new File( datsDir, "resource.dat" );

			DatBackupManager backupManager = new DatBackupManager( datsDir, backupDir );
			List<DatBackupManager.BackedUpDat> backedUpDats = backupManager.getDats();

			// Don't let dats be read-only.
			for ( DatBackupManager.BackedUpDat bud : backedUpDats ) {
				if ( bud.datFile.exists() ) bud.datFile.setWritable( true );
			}

			final int backupSteps = Math.max( 1, backedUpDats.size() );
			final int backupMilestone = progMilestone;

			DatBackupManager.ProgressListener backupProgress = new DatBackupManager.ProgressListener() {
				@Override
				public void backupStatus( String message ) {
					observer.patchingStatus( message );
				}
				@Override
				public void backupProgress( int done, int total ) {
					observer.patchingProgress( backupMilestone + progBackupMax/backupSteps*done, progMax );
				}
			};
			DatBackupManager.CancelCheck backupCancel = new DatBackupManager.CancelCheck() {
				@Override
				public boolean shouldContinue() {
					return keepRunning;
				}
			};

			// Creates the vanilla backups on first run, or verifies the existing
			// set against its recorded hashes. Refuses to proceed if the set is
			// partial or altered, rather than re-backing-up dats that may already
			// be modded -- see DatBackupManager.
			boolean restoreNeeded;
			try {
				restoreNeeded = backupManager.prepareBackups( backupProgress, backupCancel );
			}
			catch ( DatBackupManager.BackupCancelledException e ) {
				return false;
			}

			progMilestone += progBackupMax;
			observer.patchingProgress( progMilestone, progMax );
			observer.patchingStatus( null );

			if ( restoreNeeded ) {
				// Clobber current dat files with their respective backups.
				// But don't bother if we made those backups just now.
				final int clobberMilestone = progMilestone;

				DatBackupManager.ProgressListener clobberProgress = new DatBackupManager.ProgressListener() {
					@Override
					public void backupStatus( String message ) {
						observer.patchingStatus( message );
					}
					@Override
					public void backupProgress( int done, int total ) {
						observer.patchingProgress( clobberMilestone + progClobberMax/backupSteps*done, progMax );
					}
				};

				try {
					backupManager.restoreVanilla( clobberProgress, backupCancel );
				}
				catch ( DatBackupManager.BackupCancelledException e ) {
					return false;
				}
				observer.patchingStatus( null );
			}
			progMilestone += progClobberMax;
			observer.patchingProgress( progMilestone, progMax );

			if ( modFiles.isEmpty() ) {
				// No mods. Nothing else to do.
				observer.patchingProgress( progMax, progMax );
				return true;
			}

			String ultimateEncoding = null;

			packContainer = new PackContainer();
			if ( ftlDatFile.exists() ) {  // FTL 1.6.1.
				AbstractPack ftlPack = new PkgPack( ftlDatFile, "r+" );

				packContainer.setPackFor( "audio/", ftlPack );
				packContainer.setPackFor( "data/", ftlPack );
				packContainer.setPackFor( "fonts/", ftlPack );
				packContainer.setPackFor( "img/", ftlPack );
				packContainer.setPackFor( null, ftlPack );
				// Supposedly "exe_icon.png" has been observed at top-level?

				ultimateEncoding = "UTF-8";
			}
			else if ( dataDatFile.exists() && resourceDatFile.exists() ) {  // FTL 1.01-1.5.13.
				AbstractPack dataPack = new FTLPack( dataDatFile, "r+" );
				packContainer.setPackFor( "data/", dataPack );

				AbstractPack resourcePack = new FTLPack( resourceDatFile, "r+" );
				packContainer.setPackFor( "audio/", resourcePack );
				packContainer.setPackFor( "fonts/", resourcePack );
				packContainer.setPackFor( "img/", resourcePack );

				ultimateEncoding = "windows-1252";
			}
			else {
				throw new IOException( String.format( "Could not find either \"%s\" or both \"%s\" and \"%s\"", ftlDatFile.getName(), dataDatFile.getName(), resourceDatFile.getName() ) );
			}
			packContainer.setPackFor( "mod-appendix/", null );

			// Track modified innerPaths in case they're clobbered.
			List<String> moddedItems = new ArrayList<String>();

			List<String> knownPaths = new ArrayList<String>();
			for ( AbstractPack pack : packContainer.getPacks() ) {
				knownPaths.addAll( pack.list() );
			}

			List<String> knownPathsLower = new ArrayList<String>( knownPaths.size() );
			for ( String innerPath : knownPaths ) {
				knownPathsLower.add( innerPath.toLowerCase() );
			}

			List<String> knownRoots = packContainer.getRoots();

			// Group1: parentPath/, Group2: root/, Group3: fileName.
			Pattern pathPtn = Pattern.compile( "^(?:(([^/]+/)(?:.*/)?))?([^/]+)$" );

			for ( File modFile : modFiles ) {
				if ( !keepRunning ) return false;

				FileInputStream fis = null;
				ZipInputStream zis = null;
				try {
					log.info( "" );
					log.info( String.format( "Installing mod: %s", modFile.getName() ) );
					observer.patchingMod( modFile );

					fis = new FileInputStream( modFile );
					zis = new ZipInputStream( new BufferedInputStream( fis ) );
					ZipEntry item;
					while ( (item = zis.getNextEntry()) != null ) {
						if ( item.isDirectory() ) {
							zis.closeEntry();
							continue;
						}

						String innerPath = item.getName();
						innerPath = innerPath.replace( '\\', '/' );  // Non-standard zips.

						Matcher m = pathPtn.matcher( innerPath );
						if ( !m.matches() ) {
							warn( String.format( "%s: ignored an oddly named file \"%s\".", modFile.getName(), innerPath ) );
							zis.closeEntry();
							continue;
						}

						String parentPath = m.group( 1 );
						String root = m.group( 2 );
						String fileName = m.group( 3 );

						AbstractPack pack = packContainer.getPackFor( innerPath );
						if ( pack == null ) {
							if ( !knownRoots.contains( root ) ) {
								warn( String.format( "%s: ignored \"%s\" -- \"%s\" is not a folder FTL loads.", modFile.getName(), innerPath, root ) );
							} else {
								log.debug( String.format( "Ignoring innerPath with known root: %s", innerPath ) );
							}
							zis.closeEntry();
							continue;
						}

						if ( ModUtilities.isJunkFile( innerPath ) ) {
							log.warn( String.format( "Skipping junk file: %s", innerPath ) );
							zis.closeEntry();
							continue;
						}

						if ( fileName.endsWith( ".xml.append" ) || fileName.endsWith( ".append.xml" ) ) {
							innerPath = parentPath + fileName.replaceAll( "[.](?:xml[.]append|append[.]xml)$", ".xml" );
							innerPath = checkCase( innerPath, knownPaths, knownPathsLower );

							if ( !pack.contains( innerPath ) ) {
								warn( String.format( "%s: nothing was patched -- \"%s\" does not exist in FTL's resources. Check the filename, or whether an earlier mod was supposed to create it.", modFile.getName(), innerPath ) );
							}
							else {
								InputStream mainStream = null;
								try {
									mainStream = pack.getInputStream( innerPath );
									final String warnPrefix = modFile.getName() +": "+ innerPath;
									PatchWarningListener patchWarner = new PatchWarningListener() {
										@Override
										public void patchWarning( String message ) {
											warn( warnPrefix +": "+ message );
										}
									};
									InputStream mergedStream = ModUtilities.patchXMLFile( mainStream, zis, ultimateEncoding, globalPanic, pack.getName()+":"+innerPath, modFile.getName()+":"+parentPath+fileName, patchWarner );
									mainStream.close();
									pack.remove( innerPath );
									pack.add( innerPath, mergedStream );
								}
								finally {
									try {if ( mainStream != null ) mainStream.close();}
									catch ( IOException e ) {}
								}

								if ( !moddedItems.contains( innerPath ) ) {
									moddedItems.add( innerPath );
								}
							}
						}
						else if ( fileName.endsWith( ".xml.rawappend" ) || fileName.endsWith( ".rawappend.xml" ) ) {
							innerPath = parentPath + fileName.replaceAll( "[.](?:xml[.]rawappend|rawappend[.]xml)$", ".xml" );
							innerPath = checkCase( innerPath, knownPaths, knownPathsLower );

							if ( !pack.contains( innerPath ) ) {
								warn( String.format( "%s: nothing was raw-appended -- \"%s\" does not exist in FTL's resources.", modFile.getName(), innerPath ) );
							}
							else {
								log.warn( String.format( "Appending xml as raw text: %s", innerPath ) );
								InputStream mainStream = null;
								try {
									mainStream = pack.getInputStream( innerPath );
									InputStream mergedStream = ModUtilities.appendXMLFile( mainStream, zis, ultimateEncoding, pack.getName()+":"+innerPath, modFile.getName()+":"+parentPath+fileName );
									mainStream.close();
									pack.remove( innerPath );
									pack.add( innerPath, mergedStream );
								}
								finally {
									try {if ( mainStream != null ) mainStream.close();}
									catch ( IOException e ) {}
								}

								if ( !moddedItems.contains( innerPath ) ) {
									moddedItems.add( innerPath );
								}
							}
						}
						else if ( fileName.endsWith( ".xml.rawclobber" ) || fileName.endsWith( ".rawclobber.xml" ) ) {
							innerPath = parentPath + fileName.replaceAll( "[.](?:xml[.]rawclobber|rawclobber[.]xml)$", ".xml" );
							innerPath = checkCase( innerPath, knownPaths, knownPathsLower );

							log.warn( String.format( "Copying xml as raw text: %s", innerPath ) );

							// Normalize line endings to CR-LF.
							//   decodeText() reads anything and returns an LF string.
							String fixedText = ModUtilities.decodeText( zis, modFile.getName()+":"+parentPath+fileName ).text;
							fixedText = Pattern.compile("\n").matcher( fixedText ).replaceAll( "\r\n" );

							InputStream fixedStream = ModUtilities.encodeText( fixedText, ultimateEncoding, modFile.getName()+":"+parentPath+fileName+" (with new EOL)" );

							if ( !moddedItems.contains( innerPath ) ) {
								moddedItems.add( innerPath );
							} else {
								warn( String.format( "%s: overwrote \"%s\" wholesale, discarding changes earlier mods made to it.", modFile.getName(), innerPath ) );
							}

							if ( pack.contains( innerPath ) )
								pack.remove( innerPath );
							pack.add( innerPath, fixedStream );
						}
						else if ( fileName.endsWith( ".xml" ) ) {
							innerPath = checkCase( innerPath, knownPaths, knownPathsLower );

							InputStream fixedStream = ModUtilities.rebuildXMLFile( zis, ultimateEncoding, modFile.getName()+":"+parentPath+fileName );

							if ( !moddedItems.contains( innerPath ) ) {
								moddedItems.add( innerPath );
							} else {
								warn( String.format( "%s: overwrote \"%s\" wholesale, discarding changes earlier mods made to it.", modFile.getName(), innerPath ) );
							}

							if ( pack.contains( innerPath ) )
								pack.remove( innerPath );
							pack.add( innerPath, fixedStream );
						}
						else if ( fileName.endsWith( ".txt" ) ) {
							innerPath = checkCase( innerPath, knownPaths, knownPathsLower );

							// Normalize line endings for other text files to CR-LF.
							//   decodeText() reads anything and returns an LF string.
							String fixedText = ModUtilities.decodeText( zis, modFile.getName()+":"+parentPath+fileName ).text;
							fixedText = Pattern.compile("\n").matcher( fixedText ).replaceAll( "\r\n" );

							InputStream fixedStream = ModUtilities.encodeText( fixedText, ultimateEncoding, modFile.getName()+":"+parentPath+fileName+" (with new EOL)" );

							if ( !moddedItems.contains( innerPath ) ) {
								moddedItems.add( innerPath );
							} else {
								warn( String.format( "%s: overwrote \"%s\" wholesale, discarding changes earlier mods made to it.", modFile.getName(), innerPath ) );
							}

							if ( pack.contains( innerPath ) )
								pack.remove( innerPath );
							pack.add( innerPath, fixedStream );
						}
						else {
							innerPath = checkCase( innerPath, knownPaths, knownPathsLower );

							if ( !moddedItems.contains( innerPath ) ) {
								moddedItems.add( innerPath );
							} else {
								warn( String.format( "%s: overwrote \"%s\" wholesale, discarding changes earlier mods made to it.", modFile.getName(), innerPath ) );
							}

							if ( pack.contains( innerPath ) )
								pack.remove( innerPath );
							pack.add( innerPath, zis );
						}

						zis.closeEntry();
					}
				}
				finally {
					try {if ( zis != null ) zis.close();}
					catch ( Exception e ) {}

					try {if ( fis != null ) fis.close();}
					catch ( Exception e ) {}

					System.gc();
				}

				modsInstalled++;
				observer.patchingProgress( progMilestone + progModsMax/modFiles.size()*modsInstalled, progMax );
			}
			progMilestone += progModsMax;
			observer.patchingProgress( progMilestone, progMax );

			// Prune 'removed' files from dats.
			for ( AbstractPack pack : packContainer.getPacks() ) {
				observer.patchingStatus( String.format( "Repacking \"%s\"...", pack.getName() ) );

				AbstractPack.RepackResult repackResult = pack.repack();
				if ( repackResult != null ) {
					long bytesChanged = repackResult.bytesChanged;
					log.info( String.format( "Repacked \"%s\" (%d bytes affected)", pack.getName(), bytesChanged ) );
				}

				datsRepacked++;
				observer.patchingProgress( progMilestone + progRepackMax/backedUpDats.size()*datsRepacked, progMax );
			}
			progMilestone += progRepackMax;
			observer.patchingProgress( progMilestone, progMax );

			observer.patchingProgress( 100, progMax );
			return true;
		}
		finally {
			if ( packContainer != null ) {
				for ( AbstractPack pack : packContainer.getPacks() ) {
					try {pack.close();}
					catch( Exception e ) {}
				}
			}
		}
	}


	/**
	 * Checks if an innerPath exists, ignoring letter case.
	 *
	 * If there is no collision, the innerPath is added to the known lists.
	 * A warning will be logged if a path with differing case exists.
	 *
	 * @param knownPaths a list of innerPaths seen so far
	 * @param knownPathsLower a copy of knownPaths, lower-cased
	 * @return the existing path (if different), or innerPath
	 */
	private String checkCase( String innerPath, List<String> knownPaths, List<String> knownPathsLower ) {
		if ( knownPaths.contains( innerPath ) ) return innerPath;

		String lowerPath = innerPath.toLowerCase();
		int lowerIndex = knownPathsLower.indexOf( lowerPath );
		if ( lowerIndex != -1 ) {
			String knownPath = knownPaths.get( lowerIndex );
			warn( String.format( "A mod's file \"%s\" differs in letter case from FTL's \"%s\"; the existing path was used.", innerPath, knownPath ) );
			return knownPath;
		}

		knownPaths.add( innerPath );
		knownPathsLower.add( lowerPath );
		return innerPath;
	}

}
