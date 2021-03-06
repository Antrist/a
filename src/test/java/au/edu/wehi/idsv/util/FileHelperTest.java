package au.edu.wehi.idsv.util;

import au.edu.wehi.idsv.IntermediateFilesTest;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


public class FileHelperTest extends IntermediateFilesTest {
	@Test
	public void should_move() throws IOException {
		File from = testFolder.newFile();
		File to = testFolder.newFile();
		FileHelper.move(from, to, false);
		assertFalse(from.exists());
		assertTrue(to.exists());
	}
	@Test
	public void should_replace_destination() throws IOException {
		File from = testFolder.newFile();
		File to = new File(testFolder.getRoot(), "should_replace_destination.test");
		FileHelper.move(from, to, false);
		assertFalse(from.exists());
		assertTrue(to.exists());
	}
	@Test
	public void should_move_indicies() throws IOException {
		File from = new File(testFolder.getRoot(), "in.bam");
		File samtoolsbai = new File(testFolder.getRoot(), "in.bam.bai");
		File picardbai = new File(testFolder.getRoot(), "in.bai");
		File vcfidx = new File(testFolder.getRoot(), "in.bam.idx");
		File to = new File(testFolder.getRoot(), "out.bam");
		from.createNewFile();
		samtoolsbai.createNewFile();
		picardbai.createNewFile();
		vcfidx.createNewFile();
		FileHelper.move(from, to, true);
		assertFalse(from.exists());
		assertFalse(samtoolsbai.exists());
		assertFalse(picardbai.exists());
		assertFalse(vcfidx.exists());
		assertTrue(to.exists());
		assertTrue(new File(testFolder.getRoot(), "out.bai").exists());
		assertTrue(new File(testFolder.getRoot(), "out.bam.bai").exists());
		assertTrue(new File(testFolder.getRoot(), "out.bam.idx").exists());
	}
	@Test
	public void compress_directory() throws IOException {
		File zip = testFolder.newFile("test.zip");
		File tozip = testFolder.newFolder("tozip");
		File f1 = new File(tozip, "f1.txt");
		Files.write(f1.toPath(), B("file1"));
		File f2 = new File(tozip, "f2.txt");
		Files.write(f2.toPath(), B("file_2"));
		File nestedDir = new File(tozip, "nested");
		Files.createDirectory(nestedDir.toPath());
		File f3 = new File(nestedDir, "f1.txt");
		Files.write(f3.toPath(), B("this is a nested file"));
		FileHelper.zipDirectory(zip, tozip);
		int entries = 0;
		try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zip))) {
			for (ZipEntry ze = zis.getNextEntry(); ze != null; ze = zis.getNextEntry()) {
				switch (ze.getName()) {
					case "f1.txt":
					case "f2.txt":
						break;
					case "nested/f1.txt":
						byte[] buffer = new byte[(int) f3.length()];
						zis.read(buffer); //Assert.assertEquals(zis.read(buffer), ze.getSize());
						Assert.assertEquals("this is a nested file", S(buffer));
						break;
				}
				entries++;
			}
		}
		Assert.assertEquals(3, entries);
	}
	@Test
	public void should_support_tbi_issue392() throws IOException {
		File from = new File(testFolder.getRoot(), "in.vcf.gz");
		File from_tabix = new File(testFolder.getRoot(), "in.vcf.gz.tbi");
		File to = new File(testFolder.getRoot(), "out.vcf.gz");
		File to_tabix = new File(testFolder.getRoot(), "out.vcf.gz.tbi");
		from.createNewFile();
		from_tabix.createNewFile();
		FileHelper.move(from, to, true);
		assertFalse(from.exists());
		assertFalse(from_tabix.exists());
		assertTrue(to.exists());
		assertTrue(to_tabix.exists());
	}
}