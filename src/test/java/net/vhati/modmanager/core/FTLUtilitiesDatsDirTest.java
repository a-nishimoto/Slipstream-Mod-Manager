package net.vhati.modmanager.core;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Game discovery beyond the default steamapps/common locations: extra Steam
 * libraries declared in libraryfolders.vdf, and flatpak Steam.
 *
 * findDatsDir() itself reads the environment and cannot be exercised here,
 * so the candidate-list builder takes its inputs explicitly -- which is also
 * what makes the probe ordering, the one thing users depend on, testable.
 */
public class FTLUtilitiesDatsDirTest {

	private static void write( File f, String content ) throws Exception {
		f.getParentFile().mkdirs();
		Writer w = new FileWriter( f );
		try {
			w.write( content );
		}
		finally {
			w.close();
		}
	}

	/** vdf/JSON store C:\x as "C:\\x"; tests must escape temp paths likewise. */
	private static String vdfValue( File dir ) {
		return dir.getAbsolutePath().replace( "\\", "\\\\" );
	}


	// ---- parseLibraryFoldersVdf --------------------------------------------

	/** The pre-v3 format: vdf blocks with "path" "value" lines. */
	@Test
	public void parsesTheVdfStyleLibraryFolders( @TempDir File tmpDir ) throws Exception {
		File vdf = new File( tmpDir, "libraryfolders.vdf" );
		write( vdf,
			"\"libraryfolders\"\n" +
			"{\n" +
			"\t\"0\"\n" +
			"\t{\n" +
			"\t\t\"path\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\"\n" +
			"\t\t\"label\"\t\t\"\"\n" +
			"\t\t\"contentidcheckdone\"\t\t1\n" +
			"\t}\n" +
			"\t\"1\"\n" +
			"\t{\n" +
			"\t\t\"path\"\t\t\"D:\\\\SteamLibrary\"\n" +
			"\t}\n" +
			"}\n" );

		assertEquals(
			Arrays.asList( "C:\\Program Files (x86)\\Steam", "D:\\SteamLibrary" ),
			FTLUtilities.parseLibraryFoldersVdf( vdf ) );
	}

	/** The v3 rewrite made the file JSON-ish: "path": "value" with a colon. */
	@Test
	public void parsesTheJsonStyleLibraryFolders( @TempDir File tmpDir ) throws Exception {
		File vdf = new File( tmpDir, "libraryfolders.vdf" );
		write( vdf,
			"{\n" +
			"\t\"libraryfolders\": {\n" +
			"\t\t\"0\": {\n" +
			"\t\t\t\"path\": \"/home/user/.local/share/Steam\",\n" +
			"\t\t\t\"label\": \"\",\n" +
			"\t\t\t\"valid\": true\n" +
			"\t\t},\n" +
			"\t\t\"1\": {\n" +
			"\t\t\t\"path\": \"/mnt/games/SteamLibrary\"\n" +
			"\t\t}\n" +
			"\t}\n" +
			"}\n" );

		assertEquals(
			Arrays.asList( "/home/user/.local/share/Steam", "/mnt/games/SteamLibrary" ),
			FTLUtilities.parseLibraryFoldersVdf( vdf ) );
	}

	@Test
	public void emptyAndAbsentAndGarbageYieldNothing( @TempDir File tmpDir ) throws Exception {
		File garbage = new File( tmpDir, "libraryfolders.vdf" );
		write( garbage, "]]not a vdf, truly<<\n\"paths\" \"ignored-wrong-key\"\n" );
		assertEquals( Arrays.asList(), FTLUtilities.parseLibraryFoldersVdf( garbage ) );

		// "path" with an empty value is a real shape; it is not a library.
		File empties = new File( tmpDir, "empty.vdf" );
		write( empties, "\"path\"\t\t\"\"\n\"path\" \"\"\n" );
		assertEquals( Arrays.asList(), FTLUtilities.parseLibraryFoldersVdf( empties ) );

		assertEquals( Arrays.asList(),
			FTLUtilities.parseLibraryFoldersVdf( new File( tmpDir, "no-such-file.vdf" ) ) );
	}


	// ---- gatherSteamLibraries ----------------------------------------------

	@Test
	public void collectsExistingLibrariesAcrossSteamInstalls( @TempDir File tmpDir ) throws Exception {
		File lib = new File( tmpDir, "ExternalLibrary" );
		lib.mkdirs();

		// Windows Steam lists the external library...
		write( new File( tmpDir, "pf86/Steam/steamapps/libraryfolders.vdf" ),
			"\"path\"\t\t\"" + vdfValue( lib ) +"\"\n" +
			"\"path\"\t\t\"/no/such/mounted/nowhere\"\n" );
		// ...and so does the flatpak one, under a different root.
		write( new File( tmpDir, "xdg/Steam/steamapps/libraryfolders.vdf" ),
			"\"path\": \"" + vdfValue( lib ) +"\"\n" );

		List<String> libs = FTLUtilities.gatherSteamLibraries(
			new File( tmpDir, "pf86" ).getAbsolutePath(), null,
			new File( tmpDir, "xdg" ).getAbsolutePath(), null );

		// Unmounted drives dropped; the duplicate listed by both files kept once.
		assertEquals( Arrays.asList( lib.getAbsolutePath() ), libs );
	}


	// ---- buildDatsDirCandidates --------------------------------------------

	@Test
	public void coversEveryLibraryLayoutShape( @TempDir File tmpDir ) throws Exception {
		File lib = new File( tmpDir, "ExternalLibrary" );
		lib.mkdirs();

		List<File> candidates = FTLUtilities.buildDatsDirCandidates(
			null, null, null, null, null, Arrays.asList( lib.getAbsolutePath() ) );

		String app = "steamapps/common/FTL Faster Than Light";
		File base = new File( lib, app );
		assertTrue( candidates.contains( base ), "missing bare library candidate" );
		for ( String suffix : new String[] { "/resources", "/data", "/data/resources",
		                                    "/FTL.app/Contents/Resources" } ) {
			assertTrue( candidates.contains( new File( base, suffix ) ),
				"missing library candidate: "+ suffix );
		}
		assertTrue( candidates.contains( new File( lib, "SteamApps/common/FTL Faster Than Light" ) ),
			"missing legacy SteamApps spelling" );
	}

	/**
	 * The ordering contract: a user with FTL in both the classic location and
	 * an extra library gets the classic one, as before this feature existed.
	 */
	@Test
	public void classicLocationsAreProbedBeforeDerivedOnes() {
		List<File> candidates = FTLUtilities.buildDatsDirCandidates(
			"/fakehome", "/fakexdg", "/fake wine", "/fakepf86", "/fakepf",
			Arrays.asList( "/fakelib" ) );

		int classicSteamWin = candidates.indexOf( new File( "/fakepf86/Steam/steamapps/common/FTL Faster Than Light" ) );
		int classicWine = candidates.indexOf( new File( "/fake wine/drive_c/Program Files/GOG.com/Faster Than Light" ) );
		int classicMac = candidates.indexOf( new File( "/fakehome/Library/Application Support/Steam/steamapps/common/FTL Faster Than Light/FTL.app/Contents/Resources" ) );
		int library = candidates.indexOf( new File( "/fakelib/steamapps/common/FTL Faster Than Light" ) );
		int flatpak = candidates.indexOf( new File( "/fakehome/.var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/common/FTL Faster Than Light/data" ) );

		assertEquals( 0, countMissing( classicSteamWin, classicWine, classicMac, library, flatpak ),
			"expected candidate missing from list: "+ candidates );
		assertTrue( classicSteamWin < library, "classic Windows Steam must win over libraries" );
		assertTrue( classicWine < library, "classic Wine must win over libraries" );
		assertTrue( classicMac < library, "classic OSX Steam must win over libraries" );
		assertTrue( library < flatpak, "flatpak is the last resort" );
	}

	private static int countMissing( int... indices ) {
		int missing = 0;
		for ( int i : indices ) if ( i == -1 ) missing++;
		return missing;
	}

	@Test
	public void nullInputsProduceNoPhantomCandidates() {
		List<File> candidates = FTLUtilities.buildDatsDirCandidates(
			null, null, null, null, null, Arrays.asList() );

		for ( File f : candidates ) {
			assertTrue( !f.getPath().contains( ".var/app" ), "flatpak needs a home: "+ f );
			assertTrue( !f.getPath().contains( "null" ), "null leaked into a path: "+ f );
		}
		assertTrue( candidates.contains( new File( "/Applications/FTL.app/Contents/Resources" ) ) );

		List<String> paths = new ArrayList<String>();
		for ( File f : FTLUtilities.buildDatsDirCandidates( "/fakehome", null, null, null, null, Arrays.asList() ) )
			paths.add( f.getPath() );
		assertEquals( 2, countFlatpak( paths ), "expected exactly the two flatpak Steam candidates" );
	}

	private static int countFlatpak( List<String> paths ) {
		int n = 0;
		for ( String p : paths ) if ( p.contains( ".var/app/com.valvesoftware.Steam" ) ) n++;
		return n;
	}
}
