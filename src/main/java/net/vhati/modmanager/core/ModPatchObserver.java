package net.vhati.modmanager.core;

import java.io.File;


public interface ModPatchObserver {

	/**
	 * Updates a progress bar.
	 *
	 * If either arg is -1, the bar will become indeterminate.
	 *
	 * @param value the new value
	 * @param max the new maximum
	 */
	public void patchingProgress( final int value, final int max );

	/**
	 * Non-specific activity.
	 *
	 * @param message a string, or null
	 */
	public void patchingStatus( String message );

	/**
	 * A mod is about to be processed.
	 */
	public void patchingMod( File modFile );

	/**
	 * A non-fatal problem was noticed while applying a mod.
	 *
	 * Patching continues. These are the conditions that otherwise only reach
	 * modman-log.txt, which is truncated on every launch and which users do not
	 * read -- a patch that targeted a missing file, one mod clobbering another,
	 * a command that discarded content. Implementors should surface them where
	 * the user will actually see them.
	 *
	 * @param message a human-readable, self-contained description
	 */
	public void patchingWarning( String message );

	/**
	 * Patching ended.
	 *
	 * If anything went wrong, e may be non-null.
	 */
	public void patchingEnded( boolean outcome, Exception e );
}
