package xyz.nextalone.nagram.hgram;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HgramModelFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void acousticFileAloneDoesNotMarkModelInstalled() throws Exception {
        File model = temporary.newFolder();
        write(model, "am/final.mdl");
        assertFalse(HgramVoskModels.isValidModel(model));
        write(model, "conf/mfcc.conf");
        write(model, "graph/HCLr.fst");
        assertFalse(HgramVoskModels.isValidModel(model));
        write(model, "graph/Gr.fst");
        assertTrue(HgramVoskModels.isValidModel(model));
    }

    @Test
    public void sherpaModelRequiresNonemptyTokens() throws Exception {
        File model = temporary.newFolder();
        write(model, "encoder.onnx");
        write(model, "decoder.onnx");
        write(model, "joiner.onnx");
        new File(model, "tokens.txt").createNewFile();
        assertFalse(HgramSherpaRecognizer.isValidModel(model));
        write(model, "tokens.txt");
        assertTrue(HgramSherpaRecognizer.isValidModel(model));
    }

    @Test
    public void sherpaModelRejectsTokensDirectory() throws Exception {
        File model = temporary.newFolder();
        write(model, "encoder.onnx");
        write(model, "decoder.onnx");
        write(model, "joiner.onnx");
        assertTrue(new File(model, "tokens.txt").mkdir());
        write(model, "tokens.txt/unrelated");
        assertFalse(HgramSherpaRecognizer.isValidModel(model));
    }

    @Test
    public void unpackRejectsEscapingEntry() throws Exception {
        File zip = temporary.newFile("model.zip");
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../escaped"));
            out.write(1);
            out.closeEntry();
        }
        File destination = temporary.newFolder("destination");
        try {
            HgramVoskModels.unzip(zip, destination);
            fail("Archive escaped destination");
        } catch (SecurityException expected) {
            assertFalse(new File(destination.getParentFile(), "escaped").exists());
        }
    }

    private void write(File root, String name) throws Exception {
        File file = new File(root, name);
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(1);
        }
    }
}
