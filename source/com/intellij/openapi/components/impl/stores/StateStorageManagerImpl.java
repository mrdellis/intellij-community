package com.intellij.openapi.components.impl.stores;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.*;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.io.fs.IFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.picocontainer.MutablePicoContainer;
import org.picocontainer.PicoContainer;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class StateStorageManagerImpl implements StateStorageManager, Disposable {
  private Map<String, String> myMacros = new HashMap<String, String>();
  private Map<String, StateStorage> myStorages = new HashMap<String, StateStorage>();
  private TrackingPathMacroSubstitutor myPathMacroSubstitutor;
  private String myRootTagName;
  private Object mySession;
  private PicoContainer myPicoContainer;
  private Map<IFile, StateStorage> myFileToStorage = new HashMap<IFile, StateStorage>();

  public StateStorageManagerImpl(
    @Nullable final TrackingPathMacroSubstitutor pathMacroSubstitutor,
    final String rootTagName,
    @Nullable Disposable parentDisposable,
    PicoContainer picoContainer) {
    myPicoContainer = picoContainer;
    myRootTagName = rootTagName;
    myPathMacroSubstitutor = pathMacroSubstitutor;
    if (parentDisposable != null) {
      Disposer.register(parentDisposable, this);
    }
  }

  public synchronized void addMacro(String macro, String expansion) {
    myMacros.put("$" + macro + "$", expansion);
  }

  @Nullable
  public StateStorage getStateStorage(@NotNull final Storage storageSpec) throws StateStorage.StateStorageException {
    final String key = getStorageSpecId(storageSpec);
    return getStateStorage(storageSpec, key);
  }

  @Nullable
  private StateStorage getStateStorage(final Storage storageSpec, final String key) throws StateStorage.StateStorageException {
    if (myStorages.get(key) == null) {
      final StateStorage stateStorage = createStateStorage(storageSpec);
      if (stateStorage == null) return null;
      myStorages.put(key, stateStorage);
    }

    return myStorages.get(key);
  }

  @Nullable
  public StateStorage getFileStateStorage(final String fileName) {
    if (myStorages.get(fileName) == null) {
      final StateStorage stateStorage = createFileStateStorage(fileName);
      if (stateStorage == null) return null;
      myStorages.put(fileName, stateStorage);
    }

    return myStorages.get(fileName);
  }


  @Nullable
  private StateStorage createStateStorage(final Storage storageSpec) throws StateStorage.StateStorageException {
    if (!storageSpec.storageClass().equals(StorageAnnotationsDefaultValues.NullStateStorage.class)) {
      final String key = UUID.randomUUID().toString();
      ((MutablePicoContainer)myPicoContainer).registerComponentImplementation(key, storageSpec.storageClass());

      return (StateStorage)myPicoContainer.getComponentInstance(key);
    }
    else if (!storageSpec.stateSplitter().equals(StorageAnnotationsDefaultValues.NullStateSplitter.class)) {
      return createDirectoryStateStorage(storageSpec.file(), storageSpec.stateSplitter());
    }
    else {
      return createFileStateStorage(storageSpec.file());
    }
  }

  private static String getStorageSpecId(final Storage storageSpec) {
    if (!storageSpec.storageClass().equals(StorageAnnotationsDefaultValues.NullStateStorage.class)) {
      return storageSpec.storageClass().getName();
    }
    else {
      return storageSpec.file();
    }
  }

  public void clearStateStorage(@NotNull final String file) {
    myStorages.remove(file);
  }

  @Nullable
  private StateStorage createDirectoryStateStorage(final String file, final Class<? extends StateSplitter> splitterClass)
    throws StateStorage.StateStorageException {
    final String expandedFile = expandMacroses(file);
    if (expandedFile == null) {
      myStorages.put(file, null);
      return null;
    }

    final StateSplitter splitter;

    try {
      splitter = splitterClass.newInstance();
    }
    catch (InstantiationException e) {
      throw new StateStorage.StateStorageException(e);
    }
    catch (IllegalAccessException e) {
      throw new StateStorage.StateStorageException(e);
    }

    return new DirectoryBasedStorage(myPathMacroSubstitutor, expandedFile, splitter, this, myPicoContainer);
  }

  @Nullable
  StateStorage createFileStateStorage(@NotNull final String fileSpec) {
    String expandedFile = expandMacroses(fileSpec);
    if (expandedFile == null) {
      myStorages.put(fileSpec, null);
      return null;
    }

    return new FileBasedStorage(getMacroSubstitutor(fileSpec), expandedFile, myRootTagName, this, myPicoContainer) {
      @NotNull
      protected StorageData createStorageData() {
        return StateStorageManagerImpl.this.createStorageData(fileSpec);
      }
    };
  }

  protected TrackingPathMacroSubstitutor getMacroSubstitutor(@NotNull final String fileSpec) {
    return myPathMacroSubstitutor;
  }


  protected abstract XmlElementStorage.StorageData createStorageData(String storageSpec);

  private static final Pattern MACRO_PATTERN = Pattern.compile("(\\$[^\\$]*\\$)");

  @Nullable
  private String expandMacroses(final String file) {
    final Matcher matcher = MACRO_PATTERN.matcher(file);
    while (matcher.find()) {
      String m = matcher.group(1);
      if (!myMacros.containsKey(m)) {
        throw new IllegalArgumentException("Unknown macro: " + m + " in storage spec: " + file);
      }
    }


    String actualFile = file;

    for (String macro : myMacros.keySet()) {
      final String replacement = myMacros.get(macro);
      if (replacement == null) {
        return null;
      }

      actualFile = StringUtil.replace(actualFile, macro, replacement);
    }

    return actualFile;
  }

  public ExternalizationSession startExternalization() {
    ExternalizationSession session = new MyExternalizationSession();

    mySession = session;

    return session;
  }

  public SaveSession startSave(final ExternalizationSession externalizationSession)  {
    assert mySession == externalizationSession;

    SaveSession session = createSaveSession(externalizationSession);

    mySession = session;

    return session;
  }

  protected MySaveSession createSaveSession(final ExternalizationSession externalizationSession)  {
    return new MySaveSession((MyExternalizationSession)externalizationSession);
  }

  public void finishSave(final SaveSession saveSession) {
    assert mySession == saveSession;

    ((MySaveSession)saveSession).finishSave();

    mySession = null;
  }

  protected class MyExternalizationSession implements ExternalizationSession {
    CompoundExternalizationSession myCompoundExternalizationSession = new CompoundExternalizationSession();

    public void setState(@NotNull final Storage[] storageSpecs, final Object component, final String componentName, final Object state)
      throws StateStorage.StateStorageException {
      assert mySession == this;

      StateStorage stateStorage;             
      for (Storage storageSpec : storageSpecs) {
        stateStorage = getStateStorage(storageSpec);
        if (stateStorage == null) continue;

        final StateStorage.ExternalizationSession extSession = myCompoundExternalizationSession.getExternalizationSession(stateStorage);
        extSession.setState(component, componentName, state, storageSpec);
      }
    }

    public void setStateInOldStorage(Object component, final String componentName, Object state) throws StateStorage.StateStorageException {
      assert mySession == this;
      StateStorage stateStorage = getOldStorage(component, componentName, StateStorageOperation.WRITE);
      myCompoundExternalizationSession.getExternalizationSession(stateStorage).setState(component, componentName, state, null);
    }
  }

  @Nullable
  public StateStorage getOldStorage(Object component, final String componentName, final StateStorageOperation operation) throws StateStorage.StateStorageException {
    return getFileStateStorage(getOldStorageSpec(component, componentName, operation));
  }

  protected abstract String getOldStorageSpec(Object component, final String componentName, final StateStorageOperation operation)
    throws StateStorage.StateStorageException;

  protected class MySaveSession implements SaveSession {
    CompoundSaveSession myCompoundSaveSession;

    public MySaveSession(final MyExternalizationSession externalizationSession) {
      myCompoundSaveSession = new CompoundSaveSession(externalizationSession.myCompoundExternalizationSession);

      myFileToStorage.clear();
      for (StateStorage storage : myCompoundSaveSession.getStateStorages()) {
        final List<IFile> storageFiles = myCompoundSaveSession.getSaveSession(storage).getAllStorageFiles();
        for (IFile storageFile : storageFiles) {
          myFileToStorage.put(storageFile, storage);
        }
      }
    }

    public List<IFile> getAllStorageFilesToSave() throws StateStorage.StateStorageException {
      assert mySession == this;
      return myCompoundSaveSession.getAllStorageFilesToSave();
    }

    public List<IFile> getAllStorageFiles() {
      return myCompoundSaveSession.getAllStorageFiles();
    }

    public void save() throws StateStorage.StateStorageException {
      assert mySession == this;

      myCompoundSaveSession.save();
    }

    public Set<String> getUsedMacros()  {
      assert mySession == this;
      return myCompoundSaveSession.getUsedMacros();
    }

    public StateStorage.SaveSession getSaveSession(final String storage) {
      final StateStorage stateStorage = myStorages.get(storage);
      assert stateStorage != null;
      return myCompoundSaveSession.getSaveSession(stateStorage);
    }

    public void finishSave() {
      assert mySession == this;
      myCompoundSaveSession.finishSave();
    }

    //returns set of component which were changed, null if changes are much more than just component state.
    @Nullable
    public Set<String> analyzeExternalChanges(final Set<Pair<VirtualFile, StateStorage>> changedFiles) {
      Set<String> result = new HashSet<String>();

      nextSorage: for (Pair<VirtualFile, StateStorage> pair : changedFiles) {
        final StateStorage stateStorage = pair.second;
        final StateStorage.SaveSession saveSession = myCompoundSaveSession.getSaveSession(stateStorage);
        if (saveSession == null) continue nextSorage;
        final Set<String> s = saveSession.analyzeExternalChanges(changedFiles);

        if (s == null) return null;
        result.addAll(s);
      }

      return result;
    }
  }

  public void dispose() {
  }

  public void reload(final Set<Pair<VirtualFile,StateStorage>> changedFiles, @NotNull final Set<String> changedComponents) throws StateStorage.StateStorageException {
    for (Pair<VirtualFile, StateStorage> pair : changedFiles) {
      assert pair != null;
      final StateStorage storage = pair.second;
      assert storage != null : "Null storage for: " + pair.first;
      storage.reload(changedComponents);
    }
  }
}
