package net.vhati.modmanager.ui;

import java.awt.Frame;
import java.io.File;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

import net.vhati.modmanager.core.ModPatchObserver;
import net.vhati.modmanager.ui.ProgressDialog;


public class ModPatchDialog extends ProgressDialog implements ModPatchObserver {


	/** Beyond this, the rest are counted but not listed; the log has them all. */
	private static final int MAX_SHOWN_WARNINGS = 25;

	private final java.util.List<String> warnings = new java.util.ArrayList<String>();
	private int warningCount = 0;


	public ModPatchDialog( Frame owner, boolean continueOnSuccess ) {
		super( owner, continueOnSuccess );
		this.setTitle( "Patching..." );

		this.setSize( 400, 160 );
		this.setMinimumSize( this.getPreferredSize() );
		this.setLocationRelativeTo( owner );
	}


	/**
	 * Updates the progress bar.
	 *
	 * If either arg is -1, the bar will become indeterminate.
	 *
	 * @param value the new value
	 * @param max the new maximum
	 */
	@Override
	public void patchingProgress( final int value, final int max ) {
		this.setProgressLater( value, max );
	}

	/**
	 * Non-specific activity.
	 *
	 * @param message a string, or null
	 */
	@Override
	public void patchingStatus( final String message ) {
		setStatusTextLater( message != null ? message : "..." );
	}

	/**
	 * A mod is about to be processed.
	 */
	@Override
	public void patchingMod( final File modFile ) {
		setStatusTextLater( String.format( "Installing mod \"%s\"...", modFile.getName() ) );
	}

	/**
	 * Patching ended.
	 *
	 * If anything went wrong, e may be non-null.
	 */
	@Override
	public void patchingEnded( boolean outcome, Exception e ) {
		setTaskOutcomeLater( outcome, e );
	}

	/**
	 * Collects a non-fatal problem to show once patching ends.
	 *
	 * Not shown as it happens: the status area is overwritten by every
	 * subsequent status update, so a warning displayed mid-run would be gone
	 * within milliseconds.
	 */
	@Override
	public void patchingWarning( final String message ) {
		synchronized ( warnings ) {
			if ( warnings.size() < MAX_SHOWN_WARNINGS ) {
				warnings.add( message );
			}
			warningCount++;
		}
	}

	/**
	 * Returns true if patching succeeded but something was worth reporting.
	 *
	 * ManagerFrame checks this so a run with warnings does not auto-dismiss
	 * into launching FTL before the user has read them.
	 */
	public boolean hasWarnings() {
		synchronized ( warnings ) {
			return warningCount > 0;
		}
	}


	/**
	 * Holds the dialog open when there are warnings, even if the user asked to
	 * launch FTL straight after patching -- otherwise the window closes and the
	 * game starts before anyone reads why a mod did nothing.
	 */
	@Override
	protected boolean shouldContinueAutomatically() {
		return super.shouldContinueAutomatically() && !hasWarnings();
	}


	@Override
	protected void setTaskOutcome( boolean outcome, Exception e ) {
		super.setTaskOutcome( outcome, e );
		if ( !this.isShowing() ) return;

		if ( succeeded == true ) {
			StringBuilder buf = new StringBuilder();

			synchronized ( warnings ) {
				if ( warningCount == 0 ) {
					buf.append( "Patching completed." );
				}
				else {
					buf.append( String.format( "Patching completed, with %d warning(s).\n",
						warningCount ) );
					buf.append( "FTL was patched, but some mods may not have done what they intended.\n\n" );

					for ( String message : warnings ) {
						buf.append( "- " ).append( message ).append( "\n" );
					}
					if ( warningCount > warnings.size() ) {
						buf.append( String.format( "- ...and %d more (see modman-log.txt).\n",
							warningCount - warnings.size() ) );
					}
				}
			}
			setStatusText( buf.toString() );
		} else {
			setStatusText( String.format( "Patching failed: %s", e ) );
		}
	}
}
