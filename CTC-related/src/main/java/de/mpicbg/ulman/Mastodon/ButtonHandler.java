package de.mpicbg.ulman.Mastodon;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

///a single-purpose, button-event-handler, aux class
class ButtonHandler implements ActionListener
{
	//whitnessed the event already?
	private boolean buttonPressed = false;

	@Override
	public void actionPerformed(ActionEvent e)
	{ buttonPressed = true; }

	public boolean buttonPressed()
	{ return buttonPressed; }
}