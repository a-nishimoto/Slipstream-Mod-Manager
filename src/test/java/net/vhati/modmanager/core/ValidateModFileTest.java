package net.vhati.modmanager.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Characterization tests for the Validate feature.
 *
 * validateModFile encodes roughly twenty diagnostics that are pure accumulated
 * FTL field knowledge -- which audio formats each game version accepts, that
 * non-CR-LF .txt crashes FTL, that 1.6.1 swapped TTF fonts for bitmap *.font,
 * which PNG colour types the game actually likes. None of it is written down
 * anywhere else, and none of it was tested. A rewrite would lose it silently.
 *
 * Assertions are made against the rendered ReportFormatter output, which is what
 * both the GUI info pane and the CLI show, so they pin what a user actually
 * sees rather than internal structure.
 */
public class ValidateModFileTest {

	/** A harmless append file, so archives are not flagged as clobber-only mods. */
	private static final String PAD_PATH = "data/pad.xml.append";
	private static final byte[] PAD_BODY = "<foo />\r\n".getBytes( StandardCharsets.UTF_8 );


	private static File buildMod( File dir, String name, Map<String,byte[]> entries ) throws Exception {
		File modFile = new File( dir, name );
		OutputStream fos = new FileOutputStream( modFile );
		ZipOutputStream zos = new ZipOutputStream( fos );
		try {
			for ( Map.Entry<String,byte[]> entry : entries.entrySet() ) {
				zos.putNextEntry( new ZipEntry( entry.getKey() ) );
				zos.write( entry.getValue() );
				zos.closeEntry();
			}
		}
		finally {
			zos.close();
		}
		return modFile;
	}

	private static Map<String,byte[]> entries( Object... pairs ) {
		Map<String,byte[]> map = new LinkedHashMap<String,byte[]>();
		for ( int i=0; i < pairs.length; i += 2 ) {
			Object body = pairs[i+1];
			map.put( (String)pairs[i],
				body instanceof byte[] ? (byte[])body : ((String)body).getBytes( StandardCharsets.UTF_8 ) );
		}
		return map;
	}

	private static String validate( File modFile ) {
		Report report = ModUtilities.validateModFile( modFile );
		StringBuilder buf = new StringBuilder();
		new Report.ReportFormatter().format( report.messages, buf, 0 );
		return buf.toString();
	}

	private static String validateEntries( File dir, String name, Map<String,byte[]> entries ) throws Exception {
		return validate( buildMod( dir, name, entries ) );
	}


	// ---- baseline ---------------------------------------------------------

	@Test
	public void aCleanModReportsNoProblems( @TempDir File tmpDir ) throws Exception {
		String out = validateEntries( tmpDir, "clean.ftl", entries( PAD_PATH, PAD_BODY ) );
		assertTrue( out.contains( "No Problems" ), out );
	}

	/** The two mods shipped in every release must validate clean. */
	@Test
	public void theShippedModsValidateClean() throws Exception {
		File modsDir = new File( "skel_common/mods" );
		File[] shipped = modsDir.listFiles( ( d, n ) -> n.endsWith( ".ftl" ) );

		assertTrue( shipped != null && shipped.length > 0, "shipped mods missing from skel_common/mods" );
		for ( File modFile : shipped ) {
			String out = validate( modFile );
			assertTrue( out.contains( "No Problems" ), modFile.getName() +" no longer validates clean:\n"+ out );
		}
	}


	// ---- packaging rules --------------------------------------------------

	@Test
	public void aModThatOnlyClobbersIsFlagged( @TempDir File tmpDir ) throws Exception {
		String out = validateEntries( tmpDir, "clobber.ftl", entries( "data/events.xml", "<foo />\r\n" ) );
		assertTrue( out.contains( "doesn't append" ), out );
	}

	@Test
	public void unsupportedTopLevelFoldersAndStrayFilesAreFlagged( @TempDir File tmpDir ) throws Exception {
		String folder = validateEntries( tmpDir, "folder.ftl",
			entries( "src/a.txt", "a\r\n", PAD_PATH, PAD_BODY ) );
		assertTrue( folder.contains( "Unsupported top-level folder: src/" ), folder );

		String stray = validateEntries( tmpDir, "stray.ftl",
			entries( "readme.txt", "hi\r\n", PAD_PATH, PAD_BODY ) );
		assertTrue( stray.contains( "Extraneous top-level file" ), stray );
	}

	@Test
	public void junkFilesAreFlagged( @TempDir File tmpDir ) throws Exception {
		String out = validateEntries( tmpDir, "junk.ftl",
			entries( "data/.DS_Store", "x", "img/thumbs.db", "x", PAD_PATH, PAD_BODY ) );
		assertTrue( out.contains( "Junk file" ), out );
	}


	// ---- FTL version knowledge, the irreplaceable part --------------------

	@Test
	public void fontFormatAdviceIsVersionSpecific( @TempDir File tmpDir ) throws Exception {
		String bitmap = validateEntries( tmpDir, "font.ftl",
			entries( "fonts/justin.font", "xx", PAD_PATH, PAD_BODY ) );
		assertTrue( bitmap.contains( "FTL 1.6.1 won't work in FTL 1.01-1.5.13" ), bitmap );

		String ttf = validateEntries( tmpDir, "ttf.ftl",
			entries( "fonts/justin.ttf", "xx", PAD_PATH, PAD_BODY ) );
		assertTrue( ttf.contains( "switched to *.font" ), ttf );
	}

	@Test
	public void mp3AudioIsFlagged( @TempDir File tmpDir ) throws Exception {
		String out = validateEntries( tmpDir, "mp3.ftl",
			entries( "audio/music/track.mp3", "xx", PAD_PATH, PAD_BODY ) );
		assertTrue( out.contains( "MP3 audio is not supported" ), out );
	}

	@Test
	public void lineEndingRulesDifferBetweenXmlAndTxt( @TempDir File tmpDir ) throws Exception {
		// LF in an append file is merely unwise...
		String xml = validateEntries( tmpDir, "lfxml.ftl",
			entries( "data/lf.xml.append", "<foo />\n", PAD_PATH, PAD_BODY ) );
		assertTrue( xml.contains( "LF line endings" ) && xml.contains( "CR-LF is safest" ), xml );

		// ...but in a .txt it crashes the game.
		String txt = validateEntries( tmpDir, "lftxt.ftl",
			entries( "data/lf.txt", "hello\n", PAD_PATH, PAD_BODY ) );
		assertTrue( txt.contains( "Non-CR-LF txt crashes FTL" ), txt );
	}

	@Test
	public void encodingAdviceCoversBomsAndUnencodableCharacters( @TempDir File tmpDir ) throws Exception {
		byte[] bom = new byte[] {(byte)0xEF, (byte)0xBB, (byte)0xBF, '<', 'f', '/', '>', '\r', '\n'};
		String bomOut = validateEntries( tmpDir, "bom.ftl", entries( "data/bom.xml.append", bom, PAD_PATH, PAD_BODY ) );
		assertTrue( bomOut.contains( "BOM detected" ), bomOut );

		String cjk = validateEntries( tmpDir, "cjk.ftl",
			entries( "data/u8.xml.append", "<foo>中文</foo>\r\n", PAD_PATH, PAD_BODY ) );
		assertTrue( cjk.contains( "can't be re-encoded as windows-1252" ), cjk );

		String fancy = validateEntries( tmpDir, "fancy.ftl",
			entries( "data/w.xml.append",
				"<foo>café – naïve</foo>\r\n".getBytes( Charset.forName( "windows-1252" ) ),
				PAD_PATH, PAD_BODY ) );
		assertTrue( fancy.contains( "Windows-1252 encoding with fancy non-ASCII chars" ), fancy );
	}

	@Test
	public void homoglyphsAreSuggestedAgainst( @TempDir File tmpDir ) throws Exception {
		String out = validateEntries( tmpDir, "odd.ftl",
			entries( "data/odd.xml.append", "<foo>‘a’ “b” – …</foo>\r\n",
				PAD_PATH, PAD_BODY ) );
		assertTrue( out.contains( "Odd characters resembling" ), out );
	}

	@Test
	public void malformedXmlIsReportedPerParser( @TempDir File tmpDir ) throws Exception {
		// A leading newline keeps the error off line 1; see the defect below.
		String out = validateEntries( tmpDir, "mismatch.ftl",
			entries( "data/mm.xml.append", "\r\n<title>x</type>\r\n", PAD_PATH, PAD_BODY ) );
		assertTrue( out.contains( "Strict XML Parser Issues" ), out );
	}


	// ---- defects, frozen deliberately -------------------------------------

	/**
	 * DEFECT (frozen): an XML problem reported on line 1 crashes the validator
	 * and abandons the rest of the archive.
	 *
	 * The message-formatting code slices the source around the reported line and
	 * underflows for line 1, throwing StringIndexOutOfBoundsException. The user
	 * gets "An error occurred. See log for details." and no diagnostics at all --
	 * so the mods most obviously broken produce the least useful report. Putting
	 * a blank line at the top of the same file makes it report properly.
	 */
	@Test
	public void defect_anXmlErrorOnLineOneAbandonsTheWholeReport( @TempDir File tmpDir ) throws Exception {
		String out = validateEntries( tmpDir, "line1.ftl",
			entries( "data/dup.xml.append", "<foo bar='1' bar='2' />\r\n", PAD_PATH, PAD_BODY ) );

		assertTrue( out.contains( "An error occurred" ), out );
		assertFalse( out.contains( "Strict XML Parser Issues" ),
			"if this now reports properly, the defect is fixed -- update this test" );
	}

	/**
	 * DEFECT (frozen): an unreadable PNG stops the scan, so later entries are
	 * never examined and their problems go unreported.
	 */
	@Test
	public void defect_aCorruptPngAbandonsTheRestOfTheArchive( @TempDir File tmpDir ) throws Exception {
		String out = validateEntries( tmpDir, "png.ftl",
			entries( "img/bad.png", "not a png at all",
				"data/lf.txt", "hello\n",          // would otherwise be an ERROR
				PAD_PATH, PAD_BODY ) );

		assertTrue( out.contains( "An error occurred" ), out );
		assertFalse( out.contains( "Non-CR-LF txt crashes FTL" ),
			"the later entry was scanned after all -- the defect is fixed, update this test" );
	}
}
