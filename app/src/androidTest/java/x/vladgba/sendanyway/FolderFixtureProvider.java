package x.vladgba.sendanyway;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** SAF fixture in the test APK's separate process (which has no target Kotlin runtime). */
public class FolderFixtureProvider extends ContentProvider {
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    public FolderFixtureProvider() {
        names.put("root", "Album");
        names.put("root/empty", "empty");
        names.put("root/nested", "nested");
        names.put("root/nested/deep", "deep");
        names.put("root/photo.txt", "photo.txt");
        names.put("root/nested/photo.txt", "photo.txt");
        names.put("root/nested/zero.bin", "zero.bin");
        files.put("root/photo.txt", "root photo".getBytes(StandardCharsets.UTF_8));
        files.put("root/nested/photo.txt", "nested photo".getBytes(StandardCharsets.UTF_8));
        files.put("root/nested/zero.bin", new byte[0]);
    }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String id = DocumentsContract.getDocumentId(uri);
        return uri.getLastPathSegment().equals("children") ? queryChildDocuments(id, projection) : queryDocument(id, projection);
    }
    @Override public String getType(Uri uri) { return getDocumentType(DocumentsContract.getDocumentId(uri)); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        return openDocument(DocumentsContract.getDocumentId(uri), mode, null);
    }
    private Cursor queryDocument(String id, String[] projection) {
        MatrixCursor cursor = cursor(projection);
        add(cursor, id);
        return cursor;
    }
    private Cursor queryChildDocuments(String parent, String[] projection) {
        MatrixCursor cursor = cursor(projection);
        for (String id : names.keySet()) {
            int slash = id.lastIndexOf('/');
            if (slash >= 0 && id.substring(0, slash).equals(parent)) add(cursor, id);
        }
        return cursor;
    }
    private String getDocumentType(String id) {
        return files.containsKey(id) ? "application/octet-stream" : Document.MIME_TYPE_DIR;
    }
    private ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal)
            throws java.io.FileNotFoundException {
        if (!mode.equals("r") || !files.containsKey(id)) throw new java.io.FileNotFoundException(id);
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    out.write(files.get(id));
                } catch (IOException e) { throw new RuntimeException(e); }
            }).start();
            return pipe[0];
        } catch (IOException e) { throw new java.io.FileNotFoundException(e.toString()); }
    }
    private MatrixCursor cursor(String[] projection) {
        return new MatrixCursor(projection == null ? new String[] { Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE } : projection);
    }
    private void add(MatrixCursor cursor, String id) {
        Object[] row = new Object[cursor.getColumnCount()];
        for (int i = 0; i < row.length; i++) {
            switch (cursor.getColumnName(i)) {
                case Document.COLUMN_DOCUMENT_ID: row[i] = id; break;
                case Document.COLUMN_DISPLAY_NAME: row[i] = names.get(id); break;
                case Document.COLUMN_MIME_TYPE: row[i] = getDocumentType(id); break;
                case Document.COLUMN_SIZE: row[i] = files.containsKey(id) ? (long) files.get(id).length : 0L; break;
            }
        }
        cursor.addRow(row);
    }
}
