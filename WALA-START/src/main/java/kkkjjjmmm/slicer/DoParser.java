package kkkjjjmmm.slicer;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;

public class DoParser {

	public static void main(String[] args) throws IOException{		
		FileInputStream in = new FileInputStream(args[0]);
		CompilationUnit cu = JavaParser.parse(in);
		cu.accept(new OBSVisitor(), null);
		cu.accept(new SVFVisitor(), null);
		byte[] bArray = cu.toString().getBytes();
		Path p = FileSystems.getDefault().getPath(args[0]);
		Files.write(p, bArray);
		
	}

}