/*
 * Copyright 2012-2014, Luis Filipe da Cruz Nassif
 * 
 * This file is part of Indexador e Processador de Evidências Digitais (IPED).
 *
 * IPED is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * IPED is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IPED.  If not, see <http://www.gnu.org/licenses/>.
 */
package iped.engine.data;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.swing.KeyStroke;

import org.eclipse.collections.impl.list.mutable.primitive.IntArrayList;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import iped.data.IBookmarks;
import iped.engine.Version;
import iped.engine.util.SaveStateThread;
import iped.engine.util.Util;
import iped.search.SearchResult;
import iped.utils.IOUtil;

public class BitmapBookmarks implements IBookmarks {

    /**
     * 
     */
    private static final long serialVersionUID = 1L;

    private static Logger LOGGER = LoggerFactory.getLogger(BitmapBookmarks.class);

    public static String EXT = "." + Version.APP_EXT.toLowerCase(); //$NON-NLS-1$
    public static String STATEFILENAME = "bookmarks" + EXT; //$NON-NLS-1$

    static int bookmarkBits = Byte.SIZE;

    private boolean[] selected;
    HashMap<Integer, RoaringBitmap> bookmarks;
    private TreeMap<Integer, String> bookmarkNames = new TreeMap<Integer, String>();
    private TreeMap<Integer, String> bookmarkComments = new TreeMap<Integer, String>();
    private TreeMap<Integer, KeyStroke> bookmarkKeyStrokes = new TreeMap<Integer, KeyStroke>();
    private Set<Integer> reportBookmarks = new TreeSet<Integer>();
    TreeMap<Integer, Color> bookmarkColors = new TreeMap<Integer, Color>();

    private int selectedItens = 0, totalItems, lastId;

    private LinkedHashSet<String> typedWords = new LinkedHashSet<String>();
    private File indexDir;
    private File stateFile, cookie;

    // for future use when implementing extended bookmark types
    private Map<String, Serializable> extendedBookmarks;

    private transient IPEDSource ipedCase;

    private RoaringBitmap unionAll;

    public BitmapBookmarks(IPEDSource ipedCase, File modulePath) {
        this(ipedCase.getTotalItems(), ipedCase.getLastId(), modulePath);
        this.ipedCase = ipedCase;
    }

    public BitmapBookmarks(int totalItens, int lastId, final File modulePath) {
        this.totalItems = totalItens;
        this.lastId = lastId;
        selected = new boolean[lastId + 1];
        bookmarks = new HashMap<>();
        indexDir = new File(modulePath, "index"); //$NON-NLS-1$
        stateFile = new File(modulePath, STATEFILENAME);
        updateCookie();
        try {
            stateFile = stateFile.getCanonicalFile();
        } catch (IOException e) {
        }
    }

    public synchronized void updateCookie() {
        long date = indexDir.lastModified();
        String tempdir = System.getProperty("java.io.basetmpdir"); //$NON-NLS-1$
        if (tempdir == null)
            tempdir = System.getProperty("java.io.tmpdir"); //$NON-NLS-1$
        cookie = new File(tempdir, "indexer" + date + EXT); //$NON-NLS-1$
    }

    public int getLastId() {
        return lastId;
    }

    public int getTotalItens() {
        return this.totalItems;
    }

    public File getIndexDir() {
        return indexDir;
    }

    public Map<Integer, String> getBookmarkMap() {
        return bookmarkNames;
    }

    public LinkedHashSet<String> getTypedWords() {
        return typedWords;
    }

    public int getTotalChecked() {
        return selectedItens;
    }

    public boolean isChecked(int id) {
        return selected[id];
    }

    public synchronized void clearSelected() {
        selectedItens = 0;
        for (int i = 0; i < selected.length; i++) {
            selected[i] = false;
        }
    }

    public synchronized void checkAll() {
        selectedItens = totalItems;
        ipedCase.getLuceneIdStream().forEach(i -> {
            selected[ipedCase.getId(i)] = true;
        });
    }

    public List<String> getBookmarkList(int itemId) {
        ArrayList<Integer> bookmarkIds = getBookmarkIds(itemId);
        TreeSet<String> result = new TreeSet<>();
        for (Integer bookmarkId : bookmarkIds) {
            result.add(bookmarkNames.get(bookmarkId));
        }
        ArrayList<String> list = new ArrayList<>(result.size());
        list.addAll(result);
        return list;
    }

    public ArrayList<Integer> getBookmarkIds(int id) {
        ArrayList<Integer> bookmarkIds = new ArrayList<Integer>();
        if (bookmarkNames.size() > 0)
            for (int i : bookmarkNames.keySet()) {
                if (hasBookmark(id, i))
                    bookmarkIds.add(i);
            }

        return bookmarkIds;
    }

    public synchronized void addBookmark(int[] ids, int bookmark) {
        List<Integer> list = new ArrayList<>();
        for (int id : ids) {
            list.add(id);
        }
        this.addBookmark(list, bookmark);
    }

    public synchronized void addBookmark(List<Integer> ids, int bookmark) {
        RoaringBitmap bookmarkBitmap = bookmarks.get(bookmark);
        for (Iterator iterator = ids.iterator(); iterator.hasNext();) {
            Integer id = (Integer) iterator.next();
            bookmarkBitmap.add(id);
            if (unionAll != null) {
                unionAll.add(id);
            }
        }
    }

    public int getBookmarkCount(int bookmark) {
        return bookmarks.get(bookmark).getCardinality();
    }

    public final boolean hasBookmark(int id) {
        boolean hasBookmark = false;
        for (RoaringBitmap b : bookmarks.values()) {
            hasBookmark = b.contains(id);
            if (hasBookmark)
                return true;
        }
        return hasBookmark;
    }

    public final byte[] getBookmarkBits(int[] bookmarkids) {
        byte[] bits = new byte[bookmarks.size()];
        for (int bookmark : bookmarkids)
            bits[bookmark / bookmarkBits] |= 1 << (bookmark % bookmarkBits);

        return bits;
    }

    public final boolean hasBookmark(int id, int bookmark) {
        return bookmarks.get(bookmark).contains(id);
    }

    public final boolean hasBookmark(int id, int[] bookmarkIds) {
        boolean result = false;
        for (int i = 0; i < bookmarkIds.length; i++) {
            result = bookmarks.get(bookmarkIds[i]).contains(id);
            if (result) {
                return true;
            }
        }
        return false;
    }

    public synchronized void removeBookmark(List<Integer> ids, int bookmark) {
        RoaringBitmap bookmarkBitmap = bookmarks.get(bookmark);
        for (Iterator iterator = ids.iterator(); iterator.hasNext();) {
            Integer id = (Integer) iterator.next();
            bookmarkBitmap.remove(id);
        }
        unionAll = null;// invalidades unionAll
    }

    public synchronized int newBookmark(String bookmarkName) {

        int bookmarkId = getBookmarkId(bookmarkName);
        if (bookmarkId != -1)
            return bookmarkId;

        if (bookmarkNames.size() > 0)
            for (int i = 0; i <= bookmarkNames.lastKey(); i++)
                if (bookmarkNames.get(i) == null) {
                    bookmarkId = i;
                    break;
                }

        if (bookmarkId == -1) {
            bookmarkId = bookmarkNames.size();
            bookmarks.put(bookmarkId, new RoaringBitmap());
        }

        bookmarkNames.put(bookmarkId, bookmarkName);
        bookmarkComments.put(bookmarkId, null);
        bookmarkKeyStrokes.put(bookmarkId, null);
        if (bookmarkColors == null)
            bookmarkColors = new TreeMap<Integer, Color>();
        bookmarkColors.put(bookmarkId, null);

        return bookmarkId;
    }

    public synchronized void delBookmark(int bookmark) {
        if (bookmark == -1)
            return;
        bookmarkNames.remove(bookmark);
        bookmarkComments.remove(bookmark);
        bookmarkKeyStrokes.remove(bookmark);
        if (bookmarkColors == null)
            bookmarkColors = new TreeMap<Integer, Color>();
        bookmarkColors.remove(bookmark);
        reportBookmarks.remove(bookmark);
        bookmarks.remove(bookmark);
    }

    public synchronized void renameBookmark(int bookmarkId, String newBookmark) {
        if (bookmarkId != -1)
            bookmarkNames.put(bookmarkId, newBookmark);
    }

    public int getBookmarkId(String bookmarkName) {
        for (int i : bookmarkNames.keySet()) {
            if (bookmarkNames.get(i).equals(bookmarkName))
                return i;
        }
        return -1;
    }

    public String getBookmarkName(int bookmarkId) {
        return bookmarkNames.get(bookmarkId);
    }

    public synchronized void setBookmarkComment(int bookmarkId, String comment) {
        bookmarkComments.put(bookmarkId, comment);
    }

    public String getBookmarkComment(int bookmarkId) {
        return bookmarkComments.get(bookmarkId);
    }

    public synchronized void setBookmarkKeyStroke(int bookmarkId, KeyStroke key) {
        bookmarkKeyStrokes.put(bookmarkId, key);
    }

    public KeyStroke getBookmarkKeyStroke(int bookmarkId) {
        return bookmarkKeyStrokes.get(bookmarkId);
    }

    public synchronized void setInReport(int bookmarkId, boolean inReport) {
        if (inReport)
            reportBookmarks.add(bookmarkId);
        else
            reportBookmarks.remove(bookmarkId);
    }

    public boolean isInReport(int bookmarkId) {
        return reportBookmarks.contains(bookmarkId);
    }

    public SearchResult filterBookmarks(SearchResult result, Set<String> bookmarkNames) {
        RoaringBitmap union = getBookmarksUnion(bookmarkNames);

        for (int i = 0; i < result.getLength(); i++) {
            int id = result.getId(i);
            if (!(union.contains(id))) {
                result.getIds()[i] = -1;
            }
        }
        result.compactResults();
        return result;
    }

    public SearchResult filterBookmarksOrNoBookmarks(SearchResult result, Set<String> bookmarkNames) {
        RoaringBitmap union = getBookmarksOrNoBookmarksUnion(bookmarkNames);
        for (int i = 0; i < result.getLength(); i++) {
            int id = result.getId(i);
            if (!union.contains(id)) {
                result.getIds()[i] = -1;
            }
        }
        result.compactResults();
        return result;
    }

    public SearchResult filterNoBookmarks(SearchResult result) {
        RoaringBitmap unionAll = new RoaringBitmap();

        for (Entry<Integer, RoaringBitmap> e : bookmarks.entrySet()) {
            unionAll.or(e.getValue());
        }

        for (int i = 0; i < result.getLength(); i++) {
            int id = result.getId(i);
            if (unionAll.contains(i)) {
                result.getIds()[i] = -1;
            }
        }
        result.compactResults();
        return result;
    }

    public SearchResult filterChecked(SearchResult result) {
        for (int i = 0; i < result.getLength(); i++) {
            if (!selected[result.getId(i)]) {
                result.getIds()[i] = -1;
            }
        }
        result.compactResults();
        return result;
    }

    public SearchResult filterInReport(SearchResult result) {
        result = result.clone();
        for (int i = 0; i < result.getLength(); i++) {
            int itemId = result.getId(i);
            List<Integer> bookmarks = getBookmarkIds(itemId);
            boolean inReport = false;
            for (int bookmark : bookmarks)
                if (isInReport(bookmark)) {
                    inReport = true;
                    break;
                }
            if (!inReport) {
                result.getIds()[i] = -1;
            }
        }
        result.compactResults();
        return result;
    }

    @Override
    public void saveState() {
        saveState(false);
    }

    @Override
    public void saveState(boolean synchronous) {
        try {
            if (IOUtil.canWrite(stateFile) || (!stateFile.exists() && IOUtil.canCreateFile(stateFile.getParentFile())))
                saveState(stateFile, synchronous);
            else
                saveState(cookie, synchronous);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void saveState(File file) throws IOException {
        saveState(file, false);
    }

    @Override
    public void saveState(File file, boolean synchronous) throws IOException {
        LOGGER.info("Saving state sync={} to file {}", synchronous, file.getAbsolutePath()); //$NON-NLS-1$
        if (synchronous) {
            synchronized (this) {
                Util.writeObject(this, file.getAbsolutePath());
            }
        } else {
            SaveStateThread.getInstance().saveState(this, file);
        }
    }

    public synchronized void addToTypedWords(String texto) {

        if (!texto.trim().isEmpty() && !typedWords.contains(texto)) {
            typedWords.add(texto);
            saveState();
        }
    }

    public void loadState() {
        try {
            if (cookie.exists() && (!stateFile.exists() || cookie.lastModified() > stateFile.lastModified()))
                loadState(cookie);

            else if (stateFile.exists())
                loadState(stateFile);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized void loadState(File file) throws IOException, ClassNotFoundException {
        try {
            BitmapBookmarks state = load(file);

            if (state.selected != null /* && state.read != null */) {
                int len = Math.min(state.selected.length, this.selected.length);
                System.arraycopy(state.selected, 0, this.selected, 0, len);
            }

            this.bookmarks = state.bookmarks;
            this.typedWords = state.typedWords;
            this.selectedItens = state.selectedItens;
            this.bookmarkNames = state.bookmarkNames;
            this.bookmarkComments = state.bookmarkComments;
            this.bookmarkKeyStrokes = state.bookmarkKeyStrokes;
            this.reportBookmarks = state.reportBookmarks;
            this.bookmarkColors = state.bookmarkColors;
        } catch (ClassCastException e) {
            // try to load old format
            Bookmarks state = loadOldBookmarks(file);

            if (state.selected != null /* && state.read != null */) {
                int len = Math.min(state.selected.length, this.selected.length);
                System.arraycopy(state.selected, 0, this.selected, 0, len);
            }

            for (Entry<Integer, String> bname : state.bookmarkNames.entrySet()) {
                int[] ids = new int[1];
                ids[0] = state.getBookmarkId(bname.getValue());
                byte[] b = state.getBookmarkBits(ids);
                RoaringBitmap r = new RoaringBitmap();
                for (int i = 0; i < lastId; i++) {
                    if (state.hasBookmark(i, b)) {
                        r.add(i);
                    }
                }
                this.bookmarks.put(bname.getKey(), r);
            }

            this.typedWords = state.typedWords;
            this.selectedItens = state.selectedItens;
            this.bookmarkNames = state.bookmarkNames;
            this.bookmarkComments = state.bookmarkComments;
            this.bookmarkKeyStrokes = state.bookmarkKeyStrokes;
            this.reportBookmarks = state.reportBookmarks;
            this.bookmarkColors = state.bookmarkColors;
        }
    }

    public static BitmapBookmarks load(File file) throws ClassNotFoundException, IOException {
        LOGGER.info("Loading state from file " + file.getAbsolutePath()); //$NON-NLS-1$
        return (BitmapBookmarks) Util.readObject(file.getAbsolutePath());
    }

    public static Bookmarks loadOldBookmarks(File file) throws ClassNotFoundException, IOException {
        LOGGER.info("Loading state from file " + file.getAbsolutePath()); //$NON-NLS-1$
        return (Bookmarks) Util.readObject(file.getAbsolutePath());
    }

    public synchronized void setChecked(boolean value, int id) {
        if (value != selected[id]) {
            if (value)
                selectedItens++;
            else
                selectedItens--;
        }
        // seta valor na versão de visualização ou vice-versa
        selected[id] = value;
    }

    public RoaringBitmap getBookmarksUnion() {
        if (unionAll == null) {
            unionAll = new RoaringBitmap();
            for (Iterator<RoaringBitmap> iterator = bookmarks.values().iterator(); iterator.hasNext();) {
                unionAll.or(iterator.next());
            }
        }
        return unionAll.clone();
    }

    public RoaringBitmap getBookmarksUnion(Set<String> bookmarkNames) {
        IntArrayList bookmarkIds = new IntArrayList();
        for (String bookmarkName : bookmarkNames) {
            bookmarkIds.add(getBookmarkId(bookmarkName));
        }

        RoaringBitmap union = new RoaringBitmap();
        for (Entry<Integer, RoaringBitmap> e : bookmarks.entrySet()) {
            if (bookmarkIds.contains(e.getKey())) {
                union.or(e.getValue());
            }
        }
        return union;
    }

    public RoaringBitmap getBookmarksOrNoBookmarksUnion(Set<String> bookmarkNames) {
        IntArrayList bookmarkIds = new IntArrayList();
        for (String bookmarkName : bookmarkNames) {
            bookmarkIds.add(getBookmarkId(bookmarkName));
        }

        RoaringBitmap union = new RoaringBitmap();
        RoaringBitmap unionOthers = getBookmarksUnion();

        for (Entry<Integer, RoaringBitmap> e : bookmarks.entrySet()) {
            if (bookmarkIds.contains(e.getKey())) {
                union.or(e.getValue());
            }
        }

        union.orNot(unionOthers, Math.max(union.getCardinality(), unionOthers.getCardinality()) + 1);
        return union;
    }

    @Override
    public boolean hasBookmark(int id, byte[] bookmarkbits) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public synchronized void setBookmarkColor(int bookmarkId, Color color) {
        if (bookmarkColors == null)
            bookmarkColors = new TreeMap<Integer, Color>();
        bookmarkColors.put(bookmarkId, color);
    }

    @Override
    public Color getBookmarkColor(int bookmarkId) {
        return bookmarkColors == null || bookmarkId == -1 ? null : bookmarkColors.get(bookmarkId);
    }

    @Override
    public Set<Color> getUsedColors() {
        return bookmarkColors == null ? new HashSet<Color>() : new HashSet<Color>(bookmarkColors.values());
    }

    public synchronized void removeBookmarkKeyStroke(int bookmarkId) {
        bookmarkKeyStrokes.remove(bookmarkId);
    }
}
