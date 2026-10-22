package net.vhati.modmanager.ui.table;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class ChecklistTableModelTest {

	@Test
	public void initialSelectionMatchesInsertedState() {
		ChecklistTableModel<String> model = new ChecklistTableModel<String>();
		model.addItem( "default" );
		model.insertItem( 1, true, "checked" );
		model.insertItem( 2, false, "unchecked" );

		assertFalse( model.isSelected( 0 ) );
		assertTrue( model.isSelected( 1 ) );
		assertFalse( model.isSelected( 2 ) );
		assertEquals( Boolean.TRUE, model.getValueAt( 1, 0 ) );
		assertEquals( Boolean.FALSE, model.getValueAt( 2, 0 ) );
		assertEquals( Boolean.class, model.getColumnClass( 0 ) );
	}

	@Test
	public void selectionCanBeToggledThroughBothEntryPoints() {
		ChecklistTableModel<String> model = new ChecklistTableModel<String>();
		model.addItem( "payload" );

		model.setSelected( 0, true );
		assertTrue( model.isSelected( 0 ) );
		model.setSelected( 0, false );
		assertFalse( model.isSelected( 0 ) );
		model.setValueAt( Boolean.TRUE, 0, 0 );
		assertTrue( model.isSelected( 0 ) );
		model.setValueAt( Boolean.FALSE, 0, 0 );
		assertFalse( model.isSelected( 0 ) );
		assertEquals( "payload", model.getItem( 0 ) );
	}

	@Test
	public void selectionStaysWithPayloadWhenMovingInEitherDirection() {
		ChecklistTableModel<String> model = new ChecklistTableModel<String>();
		model.insertItem( 0, true, "checked" );
		model.addItem( "middle" );
		model.addItem( "last" );

		model.reorder( 0, 3 );
		assertEquals( "middle", model.getItem( 0 ) );
		assertEquals( "last", model.getItem( 1 ) );
		assertEquals( "checked", model.getItem( 2 ) );
		assertFalse( model.isSelected( 0 ) );
		assertFalse( model.isSelected( 1 ) );
		assertTrue( model.isSelected( 2 ) );

		model.reorder( 2, 0 );
		assertEquals( "checked", model.getItem( 0 ) );
		assertEquals( "middle", model.getItem( 1 ) );
		assertEquals( "last", model.getItem( 2 ) );
		assertTrue( model.isSelected( 0 ) );
		assertFalse( model.isSelected( 1 ) );
		assertFalse( model.isSelected( 2 ) );
	}
}
