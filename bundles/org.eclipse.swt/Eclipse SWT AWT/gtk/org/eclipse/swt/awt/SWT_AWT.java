/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.swt.awt;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/* SWT Imports */
import org.eclipse.swt.*;
import org.eclipse.swt.internal.Library;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Event;

/* AWT Imports */
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Canvas;
import java.awt.Frame;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;


/**
 * This class provides a bridge between SWT and AWT, so that it
 * is possible to embedded AWT components in SWT and vice versa.
 * 
 * @since 3.0
 */
public class SWT_AWT {

	/**
	 * The name of the embedded Frame class. The default class name
	 * for the platform will be used if <code>null</code>. 
	 */
	public static String embeddedFrameClass;

static boolean loaded, swingInitialized;
static Object menuSelectionManager;
static Method clearSelectionPath;

static native final int /*long*/ getAWTHandle (Canvas canvas);

static synchronized void loadLibrary () {
	if (loaded) return;
	loaded = true;
	/*
	* Note that the jawt library is loaded explicitily
	* because it cannot be found by the library loader.
	* All exceptions are caught because the library may
	* have been loaded already.
	*/
	try {
		System.loadLibrary("jawt");
	} catch (Throwable e) {}
	Library.loadLibrary("swt-awt");
}

static synchronized void initializeSwing() {
	if (swingInitialized) return;
	swingInitialized = true;
	try {
		/* Initialize the default focus traversal policy */
		Class[] emptyClass = new Class[0];
		Object[] emptyObject = new Object[0];
		Class clazz = Class.forName("javax.swing.UIManager");
		Method method = clazz.getMethod("getDefaults", emptyClass);
		if (method != null) method.invoke(clazz, emptyObject);

		/* Get the swing menu selection manager to dismiss swing popups properly */
		clazz = Class.forName("javax.swing.MenuSelectionManager");
		method = clazz.getMethod("defaultManager", emptyClass);
		if (method == null) return;
		menuSelectionManager = method.invoke(clazz, emptyObject);
		if (menuSelectionManager == null) return;
		clearSelectionPath = menuSelectionManager.getClass().getMethod("clearSelectedPath", emptyClass);
	} catch (Throwable e) {}
}

/**
 * Creates a new <code>java.awt.Frame</code>. This frame is the root for
 * the AWT components that will be embedded within the composite. In order
 * for the embedding to succeed, the composite must have been created
 * with the SWT.EMBEDDED style.
 * <p>
 * IMPORTANT: As of JDK1.5, the embedded frame does not receive mouse events.
 * When a lightweight component is added as a child of the embedded frame,
 * the cursor does not change. In order to work around both these problems, it is
 * strongly recommended that a heavyweight component such as <code>java.awt.Panel</code>
 * be added to the frame as the root of all components.
 * </p>
 * 
 * @param parent the parent <code>Composite</code> of the new <code>java.awt.Frame</code>
 * @return a <code>java.awt.Frame</code> to be the parent of the embedded AWT components
 * 
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the parent is null</li>
 *    <li>ERROR_INVALID_ARGUMENT - if the parent Composite does not have the SWT.EMBEDDED style</li> 
 * </ul>
 * 
 * @since 3.0
 */
public static Frame new_Frame (final Composite parent) {
	if (parent == null) SWT.error (SWT.ERROR_NULL_ARGUMENT);
	if ((parent.getStyle () & SWT.EMBEDDED) == 0) {
		SWT.error (SWT.ERROR_INVALID_ARGUMENT);
	}
	int /*long*/ handle = parent.embeddedHandle;
	/*
	 * Some JREs have implemented the embedded frame constructor to take an integer
	 * and other JREs take a long.  To handle this binary incompatability, use
	 * reflection to create the embedded frame.
	 */
	Class clazz = null;
	try {
		String className = embeddedFrameClass != null ? embeddedFrameClass : "sun.awt.X11.XEmbeddedFrame";
		clazz = Class.forName(className);
	} catch (Throwable e) {
		SWT.error (SWT.ERROR_NOT_IMPLEMENTED, e);		
	}
	initializeSwing ();
	Object value = null;
	Constructor constructor = null;
	try {
		constructor = clazz.getConstructor (new Class [] {int.class});
		value = constructor.newInstance (new Object [] {new Integer ((int)/*64*/handle)});
	} catch (Throwable e1) {
		try {
			constructor = clazz.getConstructor (new Class [] {long.class});
			value = constructor.newInstance (new Object [] {new Long (handle)});
		} catch (Throwable e2) {
			SWT.error (SWT.ERROR_NOT_IMPLEMENTED, e2);
		}
	}
	final Frame frame = (Frame) value;
	try {
		/* Call registerListeners() to make XEmbed focus traversal work */
		Method method = clazz.getMethod("registerListeners", null);
		if (method != null) method.invoke(value, null);
	} catch (Throwable e) {}
	if (Library.JAVA_VERSION >= Library.JAVA_VERSION(1, 4, 2)) {
		parent.addListener (SWT.Deactivate, new Listener () {
			public void handleEvent (Event event) {
				EventQueue.invokeLater(new Runnable () {
					public void run () {
						if (menuSelectionManager != null && clearSelectionPath != null) {
							try {
								clearSelectionPath.invoke(menuSelectionManager, new Object[0]);
							} catch (Throwable e) {}
						}
					}
				});
			}
		});
	}
	parent.addListener (SWT.Dispose, new Listener () {
		public void handleEvent (Event event) {
			parent.setVisible(false);
			EventQueue.invokeLater(new Runnable () {
				public void run () {
					frame.dispose ();
				}
			});
		}
	});
	parent.getDisplay().asyncExec(new Runnable() {
		public void run () {
			if (parent.isDisposed()) return;
			final Rectangle clientArea = parent.getClientArea();
			EventQueue.invokeLater(new Runnable () {
				public void run () {
					frame.setSize (clientArea.width, clientArea.height);
					frame.validate ();
				}
			});
		}
	});
	return frame;
}

/**
 * Creates a new <code>Shell</code>. This Shell is the root for
 * the SWT widgets that will be embedded within the AWT canvas. 
 * 
 * @param display the display for the new Shell
 * @param parent the parent <code>java.awt.Canvas</code> of the new Shell
 * @return a <code>Shell</code> to be the parent of the embedded SWT widgets
 * 
 * @exception IllegalArgumentException <ul>
 *    <li>ERROR_NULL_ARGUMENT - if the display is null</li>
 *    <li>ERROR_NULL_ARGUMENT - if the parent is null</li>
 * </ul>
 * 
 * @since 3.0
 */
public static Shell new_Shell (final Display display, final Canvas parent) {
	if (display == null) SWT.error (SWT.ERROR_NULL_ARGUMENT);
	if (parent == null) SWT.error (SWT.ERROR_NULL_ARGUMENT);
	int /*long*/ handle = 0;
	try {
		loadLibrary ();
		handle = getAWTHandle (parent);
	} catch (Throwable e) {
		SWT.error (SWT.ERROR_NOT_IMPLEMENTED, e);
	}
	if (handle == 0) SWT.error (SWT.ERROR_NOT_IMPLEMENTED);

	final Shell shell = Shell.gtk_new (display, handle);
	parent.addComponentListener(new ComponentAdapter () {
		public void componentResized (ComponentEvent e) {
			display.syncExec (new Runnable () {
				public void run () {
					Dimension dim = parent.getSize ();
					shell.setSize (dim.width, dim.height);
				}
			});
		}
	});
	shell.setVisible (true);
	return shell;
}
}
