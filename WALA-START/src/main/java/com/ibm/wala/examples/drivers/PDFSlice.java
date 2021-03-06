/*******************************************************************************
 * Copyright (c) 2002 - 2006 IBM Corporation.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.wala.examples.drivers;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Properties;
import java.util.Set;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.ShrikeBTMethod;
import com.ibm.wala.examples.util.WalaExamplesProperties;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.callgraph.CGNode;
import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilder;
import com.ibm.wala.ipa.callgraph.Entrypoint;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.callgraph.propagation.InstanceKey;
import com.ibm.wala.ipa.callgraph.propagation.PointerAnalysis;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.slicer.HeapStatement;
import com.ibm.wala.ipa.slicer.NormalReturnCaller;
import com.ibm.wala.ipa.slicer.NormalStatement;
import com.ibm.wala.ipa.slicer.ParamCallee;
import com.ibm.wala.ipa.slicer.ParamCaller;
import com.ibm.wala.ipa.slicer.SDG;
import com.ibm.wala.ipa.slicer.Slicer;
import com.ibm.wala.ipa.slicer.Slicer.ControlDependenceOptions;
import com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions;
import com.ibm.wala.ipa.slicer.Statement;
import com.ibm.wala.ipa.slicer.Statement.Kind;
import com.ibm.wala.properties.WalaProperties;
import com.ibm.wala.ssa.SSAAbstractInvokeInstruction;
import com.ibm.wala.ssa.SSAInstruction;
import com.ibm.wala.ssa.SSAInvokeInstruction;
import com.ibm.wala.types.TypeReference;
import com.ibm.wala.util.CancelException;
import com.ibm.wala.util.Predicate;
import com.ibm.wala.util.WalaException;
import com.ibm.wala.util.collections.ArraySet;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.debug.Assertions;
import com.ibm.wala.util.graph.Graph;
import com.ibm.wala.util.graph.GraphIntegrity;
import com.ibm.wala.util.graph.GraphIntegrity.UnsoundGraphException;
import com.ibm.wala.util.graph.GraphSlicer;
import com.ibm.wala.util.io.CommandLine;
import com.ibm.wala.util.io.FileProvider;
import com.ibm.wala.viz.DotUtil;
import com.ibm.wala.viz.NodeDecorator;
import com.ibm.wala.viz.PDFViewUtil;

/**
 * 
 * This simple example WALA application computes a slice (see {@link Slicer})
 * and fires off the PDF viewer to view a dot-ted representation of the slice.
 * 
 * This is an example program on how to use the slicer.
 * 
 * See the 'PDFSlice' launcher included in the 'launchers' directory.
 * 
 * @see Slicer
 * @author sfink
 */
public class PDFSlice {

	/**
	 * Name of the postscript file generated by dot
	 */
	private final static String PDF_FILE = "slice.pdf";

	/**
	 * Usage: PDFSlice -appJar [jar file name] -mainClass [main class] -srcCaller
	 * [method name] -srcCallee [method name] -dd [data dependence options] -cd
	 * [control dependence options] -dir [forward|backward]
	 * 
	 * <ul>
	 * <li>"jar file name" should be something like "c:/temp/testdata/java_cup.jar"
	 * <li>"main class" should beshould be something like
	 * "c:/temp/testdata/java_cup.jar"
	 * <li>"method name" should be the name of a method. This takes a slice from the
	 * statement that calls "srcCallee" from "srcCaller"
	 * <li>"data dependence options" can be one of "-full", "-no_base_ptrs",
	 * "-no_base_no_heap", "-no_heap", "-no_base_no_heap_no_cast", or "-none".
	 * </ul>
	 * 
	 * @throws CancelException
	 * @throws IllegalArgumentException
	 * @throws IOException
	 * 
	 * @see com.ibm.wala.ipa.slicer.Slicer.DataDependenceOptions
	 *      <li>"control dependence options" can be "-full" or "-none"
	 *      <li>the -dir argument tells whether to compute a forwards or backwards
	 *      slice.
	 *      </ul>
	 * 
	 */
	public static void main(String[] args) throws IllegalArgumentException, CancelException, IOException {
		run(args);
	}

	/**
	 * see {@link #main(String[])} for command-line arguments
	 * 
	 * @throws CancelException
	 * @throws IllegalArgumentException
	 * @throws IOException
	 */
	public static Process run(String[] args) throws IllegalArgumentException, CancelException, IOException {
		// parse the command-line into a Properties object
		Properties p = CommandLine.parse(args);
		// validate that the command-line has the expected format
		validateCommandLine(p);

		// run the applications
		return run(p.getProperty("appJar"), p.getProperty("mainClass"), p.getProperty("srcCaller"),
				p.getProperty("srcCallee"), goBackward(p), getDataDependenceOptions(p),
				getControlDependenceOptions(p));
	}

	public static DataDependenceOptions getDataDependenceOptions(Properties p) {
		String d = p.getProperty("dd", "full");
		for (DataDependenceOptions result : DataDependenceOptions.values()) {
			if (d.equals(result.getName())) {
				return result;
			}
		}
		Assertions.UNREACHABLE("unknown data datapendence option: " + d);
		return null;
	}

	public static ControlDependenceOptions getControlDependenceOptions(Properties p) {
		String d = p.getProperty("cd", "full");
		for (ControlDependenceOptions result : ControlDependenceOptions.values()) {
			if (d.equals(result.getName())) {
				return result;
			}
		}
		Assertions.UNREACHABLE("unknown control datapendence option: " + d);
		return null;
	}

	/**
	 * Should the slice be a backwards slice?
	 */
	private static boolean goBackward(Properties p) {
		return !p.getProperty("dir", "backward").equals("forward");
	}

	/**
	 * Compute a slice from a call statements, dot it, and fire off the PDF viewer
	 * to visualize the result
	 * 
	 * @param appJar
	 *            should be something like "c:/temp/testdata/java_cup.jar"
	 * @param mainClass
	 *            should be something like "c:/temp/testdata/java_cup.jar"
	 * @param srcCaller
	 *            name of the method containing the statement of interest
	 * @param srcCallee
	 *            name of the method called by the statement of interest
	 * @param goBackward
	 *            do a backward slice?
	 * @param dOptions
	 *            options controlling data dependence
	 * @param cOptions
	 *            options controlling control dependence
	 * @return a Process running the PDF viewer to visualize the dot'ted
	 *         representation of the slice
	 * @throws CancelException
	 * @throws IllegalArgumentException
	 */
	public static Process run(String appJar, String mainClass, String srcCaller, String srcCallee, boolean goBackward,
			DataDependenceOptions dOptions, ControlDependenceOptions cOptions)
			throws IllegalArgumentException, CancelException, IOException {
		try {
			// create an analysis scope representing the appJar as a J2SE application
			AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(appJar,
					(new FileProvider()).getFile("Java60RegressionExclusions.txt"));

			// build a class hierarchy, call graph, and system dependence graph
			ClassHierarchy cha = ClassHierarchyFactory.make(scope);
			Iterable<Entrypoint> entrypoints = com.ibm.wala.ipa.callgraph.impl.Util.makeMainEntrypoints(scope, cha,
					mainClass);
			AnalysisOptions options = new AnalysisOptions(scope, entrypoints);
			CallGraphBuilder<InstanceKey> builder = Util.makeVanillaZeroOneCFABuilder(options, new AnalysisCacheImpl(),
					cha, scope);
			// CallGraphBuilder builder = Util.makeZeroOneCFABuilder(options, new
			// AnalysisCache(), cha, scope);
			CallGraph cg = builder.makeCallGraph(options, null);
			SDG<InstanceKey> sdg = new SDG<>(cg, builder.getPointerAnalysis(), dOptions, cOptions);

			// find the call statement of interest
			CGNode callerNode = SlicerTest.findMethod(cg, srcCaller);
			Statement s = SlicerTest.findCallTo(callerNode, srcCallee);
			System.err.println("Statement: " + s);

			// compute the slice as a collection of statements
			Collection<Statement> slice = null;
			if (goBackward) {
				final PointerAnalysis<InstanceKey> pointerAnalysis = builder.getPointerAnalysis();
				slice = Slicer.computeBackwardSlice(s, cg, pointerAnalysis, dOptions, cOptions);
			} else {
				// for forward slices ... we actually slice from the return value of
				// calls.
				s = getReturnStatementForCall(s);
				final PointerAnalysis<InstanceKey> pointerAnalysis = builder.getPointerAnalysis();
				slice = Slicer.computeForwardSlice(s, cg, pointerAnalysis, dOptions, cOptions);
			}
			SlicerTest.dumpSlice(slice);

			dumpSourceLineNumbers(slice);
			
			// create a view of the SDG restricted to nodes in the slice
			Graph<Statement> g = pruneSDG(sdg, slice);

			sanityCheck(slice, g);

			// load Properties from standard WALA and the WALA examples project
			Properties p = null;
			try {
				p = WalaExamplesProperties.loadProperties();
				p.putAll(WalaProperties.loadProperties());
			} catch (WalaException e) {
				e.printStackTrace();
				Assertions.UNREACHABLE();
			}
			// create a dot representation.
			String psFile = p.getProperty(WalaProperties.OUTPUT_DIR) + File.separatorChar + PDF_FILE;
			String dotExe = "dot";//p.getProperty(WalaExamplesProperties.DOT_EXE);
			DotUtil.dotify(g, makeNodeDecorator(), PDFTypeHierarchy.DOT_FILE, psFile, dotExe);

			// fire off the PDF viewer
//			String gvExe = p.getProperty(WalaExamplesProperties.PDFVIEW_EXE);
			return PDFViewUtil.launchPDFView(psFile, "evince");

		} catch (WalaException e) {
			// something bad happened.
			e.printStackTrace();
			return null;
		}
	}

	// {className -> {methodName -> [line numbers]}}
	public static Table<String, String, Set<Integer>> dumpSourceLineNumbers(Collection<Statement> slice) {
		System.out.println(">>>>> SLICER LINES: >>>");
		Table<String, String, Set<Integer>> allstatements = HashBasedTable.create();
		for (Statement s : slice) {
			IMethod method = s.getNode().getMethod();
			String className = method.getDeclaringClass().getName().toString();
			String methodName = method.getSignature();			
				if (s.getKind() == Statement.Kind.NORMAL) { // ignore special kinds of statements
					int bcIndex, instructionIndex = ((NormalStatement) s).getInstructionIndex();
					try {
						bcIndex = ((ShrikeBTMethod) s.getNode().getMethod()).getBytecodeIndex(instructionIndex);
						try {
							int src_line_number = s.getNode().getMethod().getLineNumber(bcIndex);						
							System.err.println("Source line number = " + s.getNode().getMethod() + ":" + src_line_number);
							if(allstatements.contains(className, methodName)) {
								Set<Integer> statements = allstatements.get(className, methodName);
								statements.add(src_line_number);
								allstatements.put(className, methodName, statements);
							}else {
								 Set<Integer> statements = new ArraySet<Integer>();
								 statements.add(src_line_number);
								 allstatements.put(className, methodName, statements);
							}
						} catch (Exception e) {
							System.err.println("Bytecode index no good");
							System.err.println(e.getMessage());
						}
					} catch (Exception e) {
						System.err.println("it's probably not a BT method (e.g. it's a fakeroot method)");
						System.err.println(e.getMessage());
					}
				}		
		}
		return allstatements;
	}
	
	/**
	 * check that g is a well-formed graph, and that it contains exactly the number
	 * of nodes in the slice
	 */
	private static void sanityCheck(Collection<Statement> slice, Graph<Statement> g) {
		try {
			GraphIntegrity.check(g);
		} catch (UnsoundGraphException e1) {
			e1.printStackTrace();
			Assertions.UNREACHABLE();
		}
		Assertions.productionAssertion(g.getNumberOfNodes() == slice.size(),
				"panic " + g.getNumberOfNodes() + " " + slice.size());
	}

	/**
	 * If s is a call statement, return the statement representing the normal return
	 * from s
	 */
	public static Statement getReturnStatementForCall(Statement s) {
		if (s.getKind() == Kind.NORMAL) {
			NormalStatement n = (NormalStatement) s;
			SSAInstruction st = n.getInstruction();
			if (st instanceof SSAInvokeInstruction) {
				SSAAbstractInvokeInstruction call = (SSAAbstractInvokeInstruction) st;
				if (call.getCallSite().getDeclaredTarget().getReturnType().equals(TypeReference.Void)) {
					throw new IllegalArgumentException(
							"this driver computes forward slices from the return value of calls.\n" + "" + "Method "
									+ call.getCallSite().getDeclaredTarget().getSignature() + " returns void.");
				}
				return new NormalReturnCaller(s.getNode(), n.getInstructionIndex());
			} else {
				return s;
			}
		} else {
			return s;
		}
	}

	/**
	 * return a view of the sdg restricted to the statements in the slice
	 */
	public static Graph<Statement> pruneSDG(SDG<InstanceKey> sdg, final Collection<Statement> slice) {
		return GraphSlicer.prune(sdg, new Predicate<Statement>() {
			@Override
			public boolean test(Statement t) {
				return slice.contains(t);
			}
		});
	}

	/**
	 * @return a NodeDecorator that decorates statements in a slice for a dot-ted
	 *         representation
	 */
	public static NodeDecorator<Statement> makeNodeDecorator() {
		return s -> {
			switch (s.getKind()) {
			case HEAP_PARAM_CALLEE:
			case HEAP_PARAM_CALLER:
			case HEAP_RET_CALLEE:
			case HEAP_RET_CALLER:
				HeapStatement h = (HeapStatement) s;
				return s.getKind() + "\\n" + h.getNode() + "\\n" + h.getLocation();
			case NORMAL:
				NormalStatement n = (NormalStatement) s;
				return n.getInstruction() + "\\n" + n.getNode().getMethod().getSignature();
			case PARAM_CALLEE:
				ParamCallee paramCallee = (ParamCallee) s;
				return s.getKind() + " " + paramCallee.getValueNumber() + "\\n" + s.getNode().getMethod().getName();
			case PARAM_CALLER:
				ParamCaller paramCaller = (ParamCaller) s;
				return s.getKind() + " " + paramCaller.getValueNumber() + "\\n" + s.getNode().getMethod().getName()
						+ "\\n" + paramCaller.getInstruction().getCallSite().getDeclaredTarget().getName();
			case EXC_RET_CALLEE:
			case EXC_RET_CALLER:
			case NORMAL_RET_CALLEE:
			case NORMAL_RET_CALLER:
			case PHI:
			default:
				return s.toString();
			}
		};
	}

	/**
	 * Validate that the command-line arguments obey the expected usage.
	 * 
	 * Usage:
	 * <ul>
	 * <li>args[0] : "-appJar"
	 * <li>args[1] : something like "c:/temp/testdata/java_cup.jar"
	 * <li>args[2] : "-mainClass"
	 * <li>args[3] : something like "Lslice/TestRecursion" *
	 * <li>args[4] : "-srcCallee"
	 * <li>args[5] : something like "print" *
	 * <li>args[4] : "-srcCaller"
	 * <li>args[5] : something like "main"
	 * </ul>
	 * 
	 * @throws UnsupportedOperationException
	 *             if command-line is malformed.
	 */
	static void validateCommandLine(Properties p) {
		if (p.get("appJar") == null) {
			throw new UnsupportedOperationException("expected command-line to include -appJar");
		}
		if (p.get("mainClass") == null) {
			throw new UnsupportedOperationException("expected command-line to include -mainClass");
		}
		if (p.get("srcCallee") == null) {
			throw new UnsupportedOperationException("expected command-line to include -srcCallee");
		}
		if (p.get("srcCaller") == null) {
			throw new UnsupportedOperationException("expected command-line to include -srcCaller");
		}
	}
}
