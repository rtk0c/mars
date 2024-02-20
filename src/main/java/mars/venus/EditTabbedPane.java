package mars.venus;

import mars.*;
import mars.mips.hardware.*;
import mars.util.*;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.filechooser.FileFilter;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.dnd.*;
import java.beans.*;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;

	/*
Copyright (c) 2003-2010,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * Tabbed pane for the editor.  Each of its tabs represents an open file.
 *
 * @author Sanderson
 **/

public class EditTabbedPane extends JTabbedPane {
    EditPane editTab;
    MainPane mainPane;

    private VenusUI mainUI;
    private Editor editor;

    /**
     * Constructor for the EditTabbedPane class.
     **/

    public EditTabbedPane(VenusUI appFrame, Editor editor, MainPane mainPane) {
        super();
        this.mainUI = appFrame;
        this.editor = editor;
        initFileOpener();
        this.mainPane = mainPane;
        this.editor.setEditTabbedPane(this);
        this.setDropTarget(new DropTarget() {
            @Override
            public synchronized void drop(DropTargetDropEvent event) {
                try {
                    // We are opening the file, seems like other editors (intellij, sublime text use Move semantic too)
                    event.acceptDrop(DnDConstants.ACTION_MOVE);
                    @SuppressWarnings("unchecked")
                    List<File> droppedFiles = (List<File>) event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    for (File file : droppedFiles) {
                        openFile(file.toPath());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        this.addChangeListener(e -> {
            EditPane editPane = (EditPane) getSelectedComponent();
            if (editPane != null) {
                // New IF statement to permit free traversal of edit panes w/o invalidating
                // assembly if assemble-all is selected.  DPS 9-Aug-2011
                if (Globals.getSettings().getBooleanSetting(Settings.ASSEMBLE_ALL_ENABLED)) {
                    EditTabbedPane.this.updateTitles(editPane);
                } else {
                    EditTabbedPane.this.updateTitlesAndMenuState(editPane);
                    EditTabbedPane.this.mainPane.getExecutePane().clearPane();
                }
                editPane.tellEditingComponentToRequestFocusInWindow();
            }
        });
    }

    /**
     * The current EditPane representing a file.  Returns null if
     * no files open.
     *
     * @return the current editor pane
     */
    public EditPane getCurrentEditTab() {
        return (EditPane) this.getSelectedComponent();
    }

    /**
     * Select the specified EditPane to be the current tab.
     *
     * @param editPane The EditPane tab to become current.
     */
    public void setCurrentEditTab(EditPane editPane) {
        this.setSelectedComponent(editPane);
    }

    /**
     * If the given file is open in the tabbed pane, make it the
     * current tab.  If not opened, open it in a new tab and make
     * it the current tab.  If file is unable to be opened,
     * leave current tab as is.
     *
     * @param path Path object for the desired file.
     * @return EditPane for the specified file, or null if file is unable to be opened in an EditPane
     */
    public EditPane getCurrentEditTabForFile(Path path) {
        EditPane result = null;
        EditPane tab = getEditPaneForFile(path);
        if (tab != null) {
            if (tab != getCurrentEditTab()) {
                setCurrentEditTab(tab);
            }
            return tab;
        }
        // If no return yet, then file is not open.  Try to open it.
        if (openFile(path)) {
            result = getCurrentEditTab();
        }
        return result;
    }

    /**
     * Carries out all necessary operations to implement
     * the New operation from the File menu.
     */
    public void newFile() {
        EditPane editPane = new EditPane(this.mainUI);
        editPane.setSourceCode("", true);
        editPane.setShowLineNumbersEnabled(true);
        editPane.getFileStatus().setFileStatus(FileStatus.NEW_NOT_EDITED);
        String name = editor.getNextDefaultFilename();
        // TODO extract "add pane" routine
        editPane.getFileStatus().setPath(Path.of(name));
        this.addTab(name, editPane);

        FileStatus.reset();
        FileStatus.setName(name);
        FileStatus.set(FileStatus.NEW_NOT_EDITED);

        RegisterFile.resetRegisters();
        mainUI.setReset(true);
        mainPane.getExecutePane().clearPane();
        mainPane.setSelectedComponent(this);
        editPane.displayCaretPosition(new Point(1, 1));
        this.setSelectedComponent(editPane);
        updateTitlesAndMenuState(editPane);
        editPane.tellEditingComponentToRequestFocusInWindow();
    }


    /**
     * Carries out all necessary operations to implement
     * the Close operation from the File menu.  May return
     * false, for instance when file has unsaved changes
     * and user selects Cancel from the warning dialog.
     *
     * @return true if file was closed, false otherwise.
     */
    public boolean closeCurrentFile() {
        EditPane editPane = getCurrentEditTab();
        if (editPane != null) {
            return closeFile(editPane);
        }
        return true;
    }

    public boolean closeFile(EditPane editPane) {
        if (editsSavedOrAbandoned()) {
            this.remove(editPane);
            currentOpenFiles.remove(editPane.getFileStatus().getPath());
            mainPane.getExecutePane().clearPane();
            mainPane.setSelectedComponent(this);
            return true;
        }
        return false;
    }

    /**
     * Carries out all necessary operations to implement
     * the Close All operation from the File menu.
     *
     * @return true if files closed, false otherwise.
     */
    public boolean closeAllFiles() {
        boolean result = false;
        while (getSelectedIndex() != -1) {
            result |= closeCurrentFile();
        }
        return result;
    }

    /**
     * Saves file under existing name.  If no name, will invoke Save As.
     *
     * @return true if the file was actually saved.
     */
    public boolean saveCurrentFile() {
        EditPane editPane = getCurrentEditTab();
        if (saveFile(editPane)) {
            FileStatus.setSaved(true);
            FileStatus.setEdited(false);
            FileStatus.set(FileStatus.NOT_EDITED);
            editPane.getFileStatus().setFileStatus(FileStatus.NOT_EDITED);
            updateTitlesAndMenuState(editPane);
            return true;
        }
        return false;
    }

    // Save file associatd with specified edit pane.
    // Returns true if save operation worked, else false.
    private boolean saveFile(EditPane editPane) {
        if (editPane != null) {
            if (editPane.getFileStatus().isNew()) {
                File theFile = saveAsFile(editPane);
                if (theFile != null) {
                    editPane.getFileStatus().setPath(theFile.toPath());
                }
                return (theFile != null);
            }
            File theFile = new File(editPane.getFileStatus().getPathname());
            try {
                BufferedWriter outFileStream = new BufferedWriter(new FileWriter(theFile));
                outFileStream.write(editPane.getSource(), 0, editPane.getSource().length());
                outFileStream.close();
            } catch (java.io.IOException c) {
                JOptionPane.showMessageDialog(null, "Save operation could not be completed due to an error:\n" + c,
                        "Save Operation Failed", JOptionPane.ERROR_MESSAGE);
                return false;
            }
            return true;
        }
        return false;
    }


    /**
     * Pops up a dialog box to do "Save As" operation.  If necessary
     * an additional overwrite dialog is performed.
     *
     * @return true if the file was actually saved.
     */
    public boolean saveAsCurrentFile() {
        EditPane editPane = getCurrentEditTab();
        File theFile = saveAsFile(editPane);
        if (theFile != null) {
            FileStatus.setFile(theFile);
            FileStatus.setName(theFile.getPath());
            FileStatus.setSaved(true);
            FileStatus.setEdited(false);
            FileStatus.set(FileStatus.NOT_EDITED);
            editor.setCurrentSaveDirectory(theFile.getParent());
            editPane.getFileStatus().setPath(theFile.toPath());
            editPane.getFileStatus().setFileStatus(FileStatus.NOT_EDITED);
            updateTitlesAndMenuState(editPane);
            return true;
        }
        return false;
    }

    // perform Save As for selected edit pane.  If the save is performed,
    // return its File object.  Otherwise return null.
    private File saveAsFile(EditPane editPane) {
        File theFile = null;
        if (editPane != null) {
            JFileChooser saveDialog = null;
            boolean operationOK = false;
            while (!operationOK) {
                // Set Save As dialog directory in a logical way.  If file in
                // edit pane had been previously saved, default to its directory.
                // If a new file (mipsN.asm), default to current save directory.
                // DPS 13-July-2011
                if (editPane.getFileStatus().isNew()) {
                    saveDialog = new JFileChooser(editor.getCurrentSaveDirectory());
                } else {
                    File f = new File(editPane.getFileStatus().getPathname());
                    if (f != null) {
                        saveDialog = new JFileChooser(f.getParent());
                    } else {
                        saveDialog = new JFileChooser(editor.getCurrentSaveDirectory());
                    }
                }
                String paneFile = editPane.getFileStatus().getFilename();
                if (paneFile != null) saveDialog.setSelectedFile(new File(paneFile));
                // end of 13-July-2011 code.
                saveDialog.setDialogTitle("Save As");

                int decision = saveDialog.showSaveDialog(mainUI);
                if (decision != JFileChooser.APPROVE_OPTION) {
                    return null;
                }
                theFile = saveDialog.getSelectedFile();
                operationOK = true;
                if (theFile.exists()) {
                    int overwrite = JOptionPane.showConfirmDialog(mainUI,
                            "File " + theFile.getName() + " already exists.  Do you wish to overwrite it?",
                            "Overwrite existing file?",
                            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                    switch (overwrite) {
                        case JOptionPane.YES_OPTION:
                            operationOK = true;
                            break;
                        case JOptionPane.NO_OPTION:
                            operationOK = false;
                            break;
                        case JOptionPane.CANCEL_OPTION:
                            return null;
                        default: // should never occur
                            return null;
                    }
                }
            }
            // Either file with selected name does not exist or user wants to
            // overwrite it, so go for it!
            try {
                BufferedWriter outFileStream = new BufferedWriter(new FileWriter(theFile));
                outFileStream.write(editPane.getSource(), 0, editPane.getSource().length());
                outFileStream.close();
            } catch (java.io.IOException c) {
                JOptionPane.showMessageDialog(null, "Save As operation could not be completed due to an error:\n" + c,
                        "Save As Operation Failed", JOptionPane.ERROR_MESSAGE);
                return null;
            }
        }
        return theFile;
    }


    /**
     * Saves all files currently open in the editor.
     *
     * @return true if operation succeeded otherwise false.
     */
    public boolean saveAllFiles() {
        boolean result = false;
        int tabCount = getTabCount();
        if (tabCount > 0) {

            result = true;
            EditPane[] tabs = new EditPane[tabCount];
            EditPane savedPane = getCurrentEditTab();
            for (int i = 0; i < tabCount; i++) {
                tabs[i] = (EditPane) getComponentAt(i);
                if (tabs[i].getFileStatus().hasUnsavedEdits()) {
                    setCurrentEditTab(tabs[i]);
                    if (saveFile(tabs[i])) {
                        tabs[i].getFileStatus().setFileStatus(FileStatus.NOT_EDITED);
                        editor.setTitle(tabs[i].getFileStatus().getPathname(), tabs[i].getFileStatus().getFilename(), tabs[i].getFileStatus().getFileStatus());
                    } else {
                        result = false;
                    }
                }
            }
            setCurrentEditTab(savedPane);
            if (result) {
                EditPane editPane = getCurrentEditTab();
                FileStatus.setSaved(true);
                FileStatus.setEdited(false);
                FileStatus.set(FileStatus.NOT_EDITED);
                editPane.getFileStatus().setFileStatus(FileStatus.NOT_EDITED);
                updateTitlesAndMenuState(editPane);
            }
        }
        return result;
    }


    /**
     * Remove the pane and update menu status
     */
    public void remove(EditPane editPane) {
        super.remove(editPane);
        editPane = getCurrentEditTab(); // is now next tab or null
        if (editPane == null) {
            FileStatus.set(FileStatus.NO_FILE);
            this.editor.setTitle("", "", FileStatus.NO_FILE);
            Globals.getGui().setMenuState(FileStatus.NO_FILE);
        } else {
            FileStatus.set(editPane.getFileStatus().getFileStatus());
            updateTitlesAndMenuState(editPane);
        }
        // When last file is closed, menu is unable to respond to mnemonics
        // and accelerators.  Let's have it request focus so it may do so.
        if (getTabCount() == 0) mainUI.haveMenuRequestFocus();
    }


    // Handy little utility to update the title on the current tab and the frame title bar
    // and also to update the MARS menu state (controls which actions are enabled).
    private void updateTitlesAndMenuState(EditPane editPane) {
        editor.setTitle(editPane.getFileStatus().getPathname(), editPane.getFileStatus().getFilename(), editPane.getFileStatus().getFileStatus());
        editPane.updateStaticFileStatus(); //  for legacy code that depends on the static FileStatus (pre 4.0)
        Globals.getGui().setMenuState(editPane.getFileStatus().getFileStatus());
    }

    // Handy little utility to update the title on the current tab and the frame title bar
    // and also to update the MARS menu state (controls which actions are enabled).
    // DPS 9-Aug-2011
    private void updateTitles(EditPane editPane) {
        editor.setTitle(editPane.getFileStatus().getPathname(), editPane.getFileStatus().getFilename(), editPane.getFileStatus().getFileStatus());
        boolean assembled = FileStatus.isAssembled();
        editPane.updateStaticFileStatus(); //  for legacy code that depends on the static FileStatus (pre 4.0)
        FileStatus.setAssembled(assembled);
    }

    /**
     * If there is an EditPane for the given file pathname, return it else return null.
     *
     * @param path Path for desired file. Must be normalized.
     * @return the EditPane for this file if it is open in the editor, or null if not.
     */
    @Nullable
    public EditPane getEditPaneForFile(Path path) {
        return currentOpenFiles.get(path);
    }

    /**
     * Check whether file has unsaved edits and, if so, check with user about saving them.
     *
     * @return true if no unsaved edits or if user chooses to save them or not; false
     * if there are unsaved edits and user cancels the operation.
     */
    public boolean editsSavedOrAbandoned() {
        EditPane currentPane = getCurrentEditTab();
        if (currentPane != null && currentPane.getFileStatus().hasUnsavedEdits()) {
            String name = currentPane.getFileStatus().getFilename();
            int result = JOptionPane.showConfirmDialog(mainUI,
                    "Changes to " + name + " will be lost unless you save.  Do you wish to save all changes now?",
                    "Save program changes?",
                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            return switch (result) {
                case JOptionPane.YES_OPTION -> saveCurrentFile();
                case JOptionPane.NO_OPTION -> true;
                case JOptionPane.CANCEL_OPTION -> false;
                // should never occur
                default -> false;
            };
        } else {
            return true;
        }
    }


    private HashMap<Path, EditPane> currentOpenFiles;
    private Path mostRecentlyOpenedFile;
    private JFileChooser fileChooser;
    private int fileFilterCount;
    private ArrayList<FileFilter> fileFilterList;
    private PropertyChangeListener listenForUserAddedFileFilter;

    public void initFileOpener() {
        this.currentOpenFiles = new HashMap<>();
        this.mostRecentlyOpenedFile = null;
        this.fileChooser = new JFileChooser();
        this.listenForUserAddedFileFilter = (PropertyChangeEvent e) -> {
            if (e.getPropertyName().equals(JFileChooser.CHOOSABLE_FILE_FILTER_CHANGED_PROPERTY)) {
                FileFilter[] newFilters = (FileFilter[]) e.getNewValue();
                FileFilter[] oldFilters = (FileFilter[]) e.getOldValue();
                if (newFilters.length > fileFilterList.size()) {
                    // new filter added, so add to end of master list.
                    fileFilterList.add(newFilters[newFilters.length - 1]);
                }
            }
        };
        this.fileChooser.addPropertyChangeListener(this.listenForUserAddedFileFilter);

        // Note: add sequence is significant - last one added becomes default.
        fileFilterList = new ArrayList<>();
        fileFilterList.add(fileChooser.getAcceptAllFileFilter());
        fileFilterList.add(FilenameFinder.getFileFilter(Globals.fileExtensions, "Assembler Files", true));
        fileFilterCount = 0; // this will trigger fileChooser file filter load in next line
        setChoosableFileFilters();
    }

    /**
     * Carries out all necessary operations to implement
     * the Open operation from the File menu.  This
     * begins with an Open File dialog.
     *
     * @return true if file was opened, false otherwise.
     */
    public boolean openFile() {
        // The fileChooser's list may be rebuilt from the master ArrayList if a new filter
        // has been added by the user.
        setChoosableFileFilters();
        // get name of file to be opened and load contents into text editing area.
        fileChooser.setCurrentDirectory(new File(editor.getCurrentOpenDirectory()));
        // Set default to previous file opened, if any.  This is useful in conjunction
        // with option to assemble file automatically upon opening.  File likely to have
        // been edited externally (e.g. by Mipster).
        if (Globals.getSettings().getAssembleOnOpenEnabled() && mostRecentlyOpenedFile != null) {
            fileChooser.setSelectedFile(mostRecentlyOpenedFile.toFile());
        }

        if (fileChooser.showOpenDialog(mainUI) == JFileChooser.APPROVE_OPTION) {
            File theFile = fileChooser.getSelectedFile();
            editor.setCurrentOpenDirectory(theFile.getParent());
            // editor.setCurrentSaveDirectory(theFile.getParent());// 13-July-2011 DPS.
            if (!openFile(theFile.toPath())) {
                return false;
            }

            // possibly send this file right through to the assembler by firing Run->Assemble's
            // actionPerformed() method.
            if (theFile.canRead() && Globals.getSettings().getAssembleOnOpenEnabled()) {
                mainUI.getRunAssembleAction().actionPerformed(null);
            }
        }
        return true;
    }

    /**
     * Carries out all necessary operations to open the
     * specified file in the editor.
     *
     * @return true if file was opened, false otherwise.
     */
    public boolean openFile(Path theFile) {
        // We only normalize the path (remove redundant components) rather than doing a full link expansion because that's the right UX.
        // If a user opened a file through a symlink, that's probably what they wanted the file to be called. Otherwise, that symlink would not have existed.
        theFile = theFile.normalize();

        // If this file is currently already open, then simply select its tab
        EditPane editPane = getEditPaneForFile(theFile);
        if (editPane != null) {
            setSelectedComponent(editPane);
            // updateTitlesAndMenuState(editPane);
            updateTitles(editPane);
            return false;
        }

        editPane = new EditPane(mainUI);
        editPane.getFileStatus().setPath(theFile);

        String currentFilePath = theFile.toString();
        // FileStatus.reset();
        FileStatus.setName(currentFilePath);
        FileStatus.setFile(theFile.toFile());
        FileStatus.set(FileStatus.OPENING);// DPS 9-Aug-2011

        if (Files.isReadable(theFile)) {
            Globals.program = new MIPSprogram();
            try {
                Globals.program.readSource(currentFilePath);
            } catch (ProcessingException pe) {
            }
            // DPS 1 Nov 2006.  Defined a StringBuffer to receive all file contents,
            // one line at a time, before adding to the Edit pane with one setText.
            // StringBuilder is preallocated to full filelength to eliminate dynamic
            // expansion as lines are added to it. Previously, each line was appended
            // to the Edit pane as it was read, way slower due to dynamic string alloc.
            StringBuilder fileContents;
            try {
                fileContents = new StringBuilder((int) Files.size(theFile));
                int lineNumber = 1;
                String line = Globals.program.getSourceLine(lineNumber++);
                while (line != null) {
                    fileContents.append(line).append("\n");
                    line = Globals.program.getSourceLine(lineNumber++);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            editPane.setSourceCode(fileContents.toString(), true);
            // The above operation generates an undoable edit, setting the initial
            // text area contents, that should not be seen as undoable by the Undo
            // action.  Let's get rid of it.
            editPane.discardAllUndoableEdits();
            editPane.setShowLineNumbersEnabled(true);
            editPane.getFileStatus().setFileStatus(FileStatus.NOT_EDITED);

            addTab(editPane.getFileStatus().getFilename(), editPane);
            setToolTipTextAt(indexOfComponent(editPane), editPane.getFileStatus().getPathname());
            setSelectedComponent(editPane);
            FileStatus.setSaved(true);
            FileStatus.setEdited(false);
            FileStatus.set(FileStatus.NOT_EDITED);

            // If assemble-all, then allow opening of any file w/o invalidating assembly.
            // DPS 9-Aug-2011
            if (Globals.getSettings().getBooleanSetting(mars.Settings.ASSEMBLE_ALL_ENABLED)) {
                updateTitles(editPane);
            } else {// this was the original code...
                updateTitlesAndMenuState(editPane);
                mainPane.getExecutePane().clearPane();
            }

            mainPane.setSelectedComponent(EditTabbedPane.this);
            editPane.tellEditingComponentToRequestFocusInWindow();
            mostRecentlyOpenedFile = theFile;

            currentOpenFiles.put(theFile, editPane);
        }
        return true;
    }


    // Private method to generate the file chooser's list of choosable file filters.
    // It is called when the file chooser is created, and called again each time the Open
    // dialog is activated.  We do this because the user may have added a new filter
    // during the previous dialog.  This can be done by entering e.g. *.txt in the file
    // name text field.  Java is funny, however, in that if the user does this then
    // cancels the dialog, the new filter will remain in the list BUT if the user does
    // this then ACCEPTS the dialog, the new filter will NOT remain in the list.  However
    // the act of entering it causes a property change event to occur, and we have a
    // handler that will add the new filter to our internal filter list and "restore" it
    // the next time this method is called.  Strangely, if the user then similarly
    // adds yet another new filter, the new one becomes simply a description change
    // to the previous one, the previous object is modified AND NO PROPERTY CHANGE EVENT
    // IS FIRED!  I could obviously deal with this situation if I wanted to, but enough
    // is enough.  The limit will be one alternative filter at a time.
    // DPS... 9 July 2008

    private void setChoosableFileFilters() {
        // See if a new filter has been added to the master list.  If so,
        // regenerate the fileChooser list from the master list.
        if (fileFilterCount < fileFilterList.size() ||
                fileFilterList.size() != fileChooser.getChoosableFileFilters().length) {
            fileFilterCount = fileFilterList.size();
            // First, "deactivate" the listener, because our addChoosableFileFilter
            // calls would otherwise activate it!  We want it to be triggered only
            // by MARS user action.
            boolean activeListener = false;
            if (fileChooser.getPropertyChangeListeners().length > 0) {
                fileChooser.removePropertyChangeListener(listenForUserAddedFileFilter);
                activeListener = true;  // we'll note this, for re-activation later
            }
            // clear out the list and populate from our own ArrayList.
            // Last one added becomes the default.
            fileChooser.resetChoosableFileFilters();
            for (int i = 0; i < fileFilterList.size(); i++) {
                fileChooser.addChoosableFileFilter((FileFilter) fileFilterList.get(i));
            }
            // Restore listener.
            if (activeListener) {
                fileChooser.addPropertyChangeListener(listenForUserAddedFileFilter);
            }
        }
    }
}
