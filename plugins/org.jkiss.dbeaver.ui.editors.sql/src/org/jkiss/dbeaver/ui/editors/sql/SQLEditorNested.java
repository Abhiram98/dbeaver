/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.editors.sql;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.events.FocusListener;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.IWorkbenchPartSite;
import org.eclipse.ui.part.MultiPageEditorSite;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.compile.DBCCompileLog;
import org.jkiss.dbeaver.model.exec.compile.DBCSourceHost;
import org.jkiss.dbeaver.model.preferences.DBPPropertySource;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.*;
import org.jkiss.dbeaver.ui.controls.ObjectCompilerLogViewer;
import org.jkiss.dbeaver.ui.controls.ProgressPageControl;
import org.jkiss.dbeaver.ui.editors.DatabaseEditorUtils;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.editors.IDatabasePostSaveProcessor;
import org.jkiss.dbeaver.ui.editors.entity.EntityEditor;

import java.util.Map;

/**
 * SQLEditorNested
 */
public abstract class SQLEditorNested<T extends DBSObject>
    extends SQLEditorBase
    implements IActiveWorkbenchPart, IRefreshablePart, DBCSourceHost, IDatabasePostSaveProcessor {

    private static final String SAVE_CONTEXT_COMPILE_PARAM = "object.compiled";

    private EditorPageControl pageControl;
    private ObjectCompilerLogViewer compileLog;
    private Control editorControl;
    private SashForm editorSash;
    private boolean activated;

    public SQLEditorNested() {
        super();

        setDocumentProvider(new SourceObjectDocumentProvider());
    }

    public IDatabaseEditorInput getDatabaseEditorInput() {
        return (IDatabaseEditorInput) super.getEditorInput();
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public T getSourceObject() {
        IEditorInput editorInput = getEditorInput();
        if (!(editorInput instanceof IDatabaseEditorInput)) {
            return null;
        }
        return (T) ((IDatabaseEditorInput) editorInput).getDatabaseObject();
    }

    @Nullable
    @Override
    public DBCExecutionContext getExecutionContext() {
        IEditorInput editorInput = getEditorInput();
        if (!(editorInput instanceof IDatabaseEditorInput)) {
            return null;
        }
        return ((IDatabaseEditorInput) editorInput).getExecutionContext();
    }

    public DBPPropertySource getInputPropertySource() {
        return getDatabaseEditorInput().getPropertySource();
    }

    @Override
    public void createPartControl(Composite parent) {
        pageControl = new EditorPageControl(parent, SWT.SHEET);

        boolean hasCompiler = getCompileCommandId() != null;

        if (hasCompiler) {
            editorSash = new SashForm(pageControl.createContentContainer(), SWT.VERTICAL | SWT.SMOOTH);
            super.createPartControl(editorSash);

            editorControl = editorSash.getChildren()[0];
            compileLog = new ObjectCompilerLogViewer(editorSash, this, false);
        } else {
            super.createPartControl(pageControl.createContentContainer());
        }

        // Create new or substitute progress control
        pageControl.createOrSubstituteProgressPanel(getSite());
        pageControl.setInfo("Source");

        if (hasCompiler) {
            editorSash.setWeights(70, 30);
            editorSash.setMaximizedControl(editorControl);
        }

        // Use focus to activate page control
        final Control editorControl = getEditorControl();
        assert editorControl != null;
        editorControl.addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                if (pageControl != null && !pageControl.isDisposed()) {
                    pageControl.activate(true);
                }
            }

            @Override
            public void focusLost(FocusEvent e) {
                if (pageControl != null && !pageControl.isDisposed()) {
                    pageControl.activate(false);
                }
            }
        });
    }

    @Override
    public void doSave(final IProgressMonitor progressMonitor) {
        UIUtils.syncExec(() -> SQLEditorNested.super.doSave(progressMonitor));
    }

    @Override
    public void runPostSaveCommands(@NotNull Map<String, Object> context) {
        String compileCommandId = getCompileCommandId();
        if (compileCommandId != null && context.get(SAVE_CONTEXT_COMPILE_PARAM) == null) {
            // Compile after save
            try {
                ActionUtils.runCommand(compileCommandId, getSite().getWorkbenchWindow());
            } finally {
                context.put(SAVE_CONTEXT_COMPILE_PARAM, true);
            }
        }
    }

    @Override
    public void activatePart() {
        if (!activated) {
            reloadSyntaxRules();
            activated = true;
        }
    }

    @Override
    public void deactivatePart() {
    }

    @Override
    public RefreshResult refreshPart(Object source, boolean force) {
        // Check if we are in saving process
        // If so then no refresh needed (source text was updated during save)
        IEditorSite editorSite = getEditorSite();
        if (editorSite instanceof MultiPageEditorSite &&
            ((MultiPageEditorSite) editorSite).getMultiPageEditor() instanceof EntityEditor &&
            ((EntityEditor) ((MultiPageEditorSite) editorSite).getMultiPageEditor()).isSaveInProgress()) {
            return RefreshResult.IGNORED;
        }

        final IDocumentProvider documentProvider = getDocumentProvider();
        if (documentProvider instanceof SQLObjectDocumentProvider) {
            ((SQLObjectDocumentProvider) documentProvider).setSourceText(null);
        }
        if (force) {
            StyledText editorControl = getEditorControl();
            if (editorControl != null) {
                int caretOffset = editorControl.getCaretOffset();
                super.setInput(getEditorInput());
                // Try to keep cursor position
                if (caretOffset < editorControl.getCharCount()) {
                    editorControl.setCaretOffset(caretOffset);
                }
            }
        }
        reloadSyntaxRules();

        return RefreshResult.REFRESHED;
    }

    protected String getCompileCommandId() {
        return null;
    }

    public boolean isDocumentLoaded() {
        final IDocumentProvider documentProvider = getDocumentProvider();
        if (documentProvider instanceof SQLObjectDocumentProvider) {
            return ((SQLObjectDocumentProvider) documentProvider).isSourceLoaded();
        }
        return true;
    }

    @Override
    public DBCCompileLog getCompileLog() {
        return compileLog;
    }

    @Override
    public void setCompileInfo(String message, boolean error) {
        pageControl.setInfo(message);
    }

    @Override
    public void positionSource(int line, int position) {
        try {
            TextViewer textViewer = getTextViewer();
            if (textViewer != null) {
                final IRegion lineInfo = textViewer.getDocument().getLineInformation(line - 1);
                final int offset = lineInfo.getOffset() + position - 1;
                super.selectAndReveal(offset, 1);
            }
            //textEditor.setFocus();
        } catch (BadLocationException e) {
            log.warn(e);
            // do nothing
        }
    }

    @Override
    public void showCompileLog() {
        editorSash.setMaximizedControl(null);
        compileLog.layoutLog();
    }

    protected abstract String getSourceText(DBRProgressMonitor monitor)
        throws DBException;

    protected abstract void setSourceText(DBRProgressMonitor monitor, String sourceText);

    protected void contributeEditorCommands(IContributionManager toolBarManager) {
        toolBarManager.add(ActionUtils.makeCommandContribution(getSite().getWorkbenchWindow(), SQLEditorCommands.CMD_OPEN_FILE));
        toolBarManager.add(ActionUtils.makeCommandContribution(getSite().getWorkbenchWindow(), SQLEditorCommands.CMD_SAVE_FILE));
        String compileCommandId = getCompileCommandId();
        if (compileCommandId != null) {
            toolBarManager.add(new Separator());
            toolBarManager.add(ActionUtils.makeCommandContribution(getSite().getWorkbenchWindow(), compileCommandId));
            toolBarManager.add(new ViewLogAction());
        }
    }

    @Override
    public void editorContextMenuAboutToShow(IMenuManager menu) {
        super.editorContextMenuAboutToShow(menu);
        menu.add(new Separator());
        contributeEditorCommands(menu);
    }

    @Override
    public void doSaveAs() {
        saveToExternalFile();
    }

    private class EditorPageControl extends ProgressPageControl {

        EditorPageControl(Composite parent, int style) {
            super(parent, style);
        }

        @Override
        public void fillCustomActions(IContributionManager contributionManager) {
            IWorkbenchPartSite site = getSite();
            if (site != null) {
                DatabaseEditorUtils.contributeStandardEditorActions(site, contributionManager);
            }
            contributeEditorCommands(contributionManager);
        }
    }

    public class ViewLogAction extends Action {
        ViewLogAction() {
            super("View compile log", DBeaverIcons.getImageDescriptor(UIIcon.COMPILE_LOG)); //$NON-NLS-2$
        }

        @Override
        public void run() {
            TextViewer textViewer = getTextViewer();
            if (textViewer == null || textViewer.getControl().isDisposed()) {
                return;
            }
            if (editorSash.getMaximizedControl() == null) {
                editorSash.setMaximizedControl(editorControl);
            } else {
                showCompileLog();
            }
        }

    }

    private class SourceObjectDocumentProvider extends SQLObjectDocumentProvider {

        public SourceObjectDocumentProvider() {
            super(SQLEditorNested.this);
        }

        @Override
        protected String loadSourceText(DBRProgressMonitor monitor) throws DBException {
            return SQLEditorNested.this.getSourceText(monitor);
        }

        @Override
        protected void saveSourceText(DBRProgressMonitor monitor, String text) throws DBException {
            SQLEditorNested.this.setSourceText(monitor, text);
        }

        @Override
        protected DBSObject getProviderObject() {
            return getSourceObject();
        }

        @Override
        public boolean isReadOnly(Object element) {
            return super.isReadOnly(element);
        }
    }
}
