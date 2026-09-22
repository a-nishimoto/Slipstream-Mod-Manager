package net.vhati.modmanager.core;


/**
 * Receives non-fatal problems noticed while a mod is applied.
 *
 * These are conditions the patcher can carry on past but a mod author or user
 * would want to know about: a patch that targeted a file the pack does not
 * contain, one mod overwriting another's work, a command that discarded content
 * it was not asked to. Left unreported they turn into "the mod did nothing",
 * which is the single most common Slipstream support question.
 */
public interface PatchWarningListener {

	/**
	 * @param message a human-readable, self-contained description of the problem
	 */
	public void patchWarning( String message );
}
