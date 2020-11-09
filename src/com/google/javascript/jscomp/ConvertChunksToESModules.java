/*
 * Copyright 2011 The Closure Compiler Authors.
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
package com.google.javascript.jscomp;

import com.google.javascript.jscomp.NodeTraversal.AbstractPostOrderCallback;
import com.google.javascript.jscomp.parsing.parser.FeatureSet;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds all references to global symbols in a different output chunk and add ES Module imports and
 * exports for them.
 *
 * <p>Given the name of the global object is NS
 *
 * <pre> var a = 1; function b() { return a }</pre>
 *
 * becomes
 *
 * <pre> var a = 1; var b = function b() { return a }; export {a};</pre>
 *
 * This allows splitting code into es modules that depend on each other's symbols, without using
 * polluting JavaScript's global scope.
 */
final class ConvertChunksToESModules implements CompilerPass {
  private final AbstractCompiler compiler;
  private final Map<JSModule, Set<String>> crossChunkExports = new HashMap<>();
  private final Map<JSModule, Map<JSModule, Set<String>>> crossChunkImports = new HashMap<>();

  static final DiagnosticType ASSIGNMENT_TO_IMPORT = DiagnosticType.error(
      "JSC_IMPORT_ASSIGN",
      "Imported symbol \"{0}\" in chunk \"{1}\" cannot be assigned");

  /**
   * Constructor for the ConvertChunksToESModules compiler pass.
   *
   * @param compiler The JSCompiler, for reporting code changes.
   */
  ConvertChunksToESModules(AbstractCompiler compiler) {
    this.compiler = compiler;
  }

  @Override
  public void process(Node externs, Node root) {
    // Find global names that are used in more than one chunk. Those that
    // are have to have import and export statements added.
    NodeTraversal.traverse(compiler, root, new FindCrossChunkReferences());

    // Force every output chunk to parse as an ES Module. If a chunk has no imports and
    // no exports, add an empty export list to generate an empty export statement:
    // example: export {};
    for (JSModule chunk : compiler.getModuleGraph().getAllModules()) {
      if (!crossChunkExports.containsKey(chunk) &&
          !crossChunkImports.containsKey(chunk) &&
          chunk.getInputs().size() > 0) {
        crossChunkExports.put(chunk, new HashSet<>());
      }
    }

    // Add export statements to chunks
    for (Map.Entry<JSModule, Set<String>> jsModuleExports : crossChunkExports.entrySet()) {
      CompilerInput lastInput =
          jsModuleExports.getKey().getInput(jsModuleExports.getKey().getInputCount() - 1);
      Node astRoot = lastInput.getAstRoot(compiler);
      Node moduleBody =
          astRoot.getFirstChild() != null && astRoot.getFirstChild().isModuleBody()
              ? astRoot.getFirstChild()
              : null;
      if (moduleBody == null) {
        FeatureSet scriptFeatures = NodeUtil.getFeatureSetOfScript(astRoot);
        scriptFeatures = scriptFeatures.union(FeatureSet.ES6_MODULES);
        astRoot.putProp(Node.FEATURE_SET, scriptFeatures);
        moduleBody = new Node(Token.MODULE_BODY);
        moduleBody.useSourceInfoFrom(astRoot);
        moduleBody.addChildrenToFront(astRoot.removeChildren());
        astRoot.addChildToFront(moduleBody);
      }

      Node exportSpecs = new Node(Token.EXPORT_SPECS);
      for (String name : jsModuleExports.getValue()) {
        Node exportSpec = new Node(Token.EXPORT_SPEC);
        exportSpec.addChildToFront(IR.name(name));
        exportSpec.addChildToFront(IR.name(name));
        exportSpec.putIntProp(Node.IS_SHORTHAND_PROPERTY, 1);
        exportSpecs.addChildToBack(exportSpec);
      }
      Node export = IR.export(exportSpecs).useSourceInfoFromForTree(astRoot);
      moduleBody.addChildToBack(export);
      compiler.reportChangeToChangeScope(astRoot);
    }

    // Add import statements to chunks
    for (Map.Entry<JSModule, Map<JSModule, Set<String>>> jsModuleImports :
        crossChunkImports.entrySet()) {
      ArrayList<Node> importStatements = new ArrayList<>();
      CompilerInput firstInput = jsModuleImports.getKey().getInput(0);
      Node astRoot = firstInput.getAstRoot(compiler);
      Node moduleBody =
          astRoot.getFirstChild() != null && astRoot.getFirstChild().isModuleBody()
              ? astRoot.getFirstChild()
              : null;
      if (moduleBody == null) {
        FeatureSet scriptFeatures = NodeUtil.getFeatureSetOfScript(astRoot);
        scriptFeatures = scriptFeatures.union(FeatureSet.ES6_MODULES);
        astRoot.putProp(Node.FEATURE_SET, scriptFeatures);
        moduleBody = new Node(Token.MODULE_BODY);
        moduleBody.useSourceInfoFrom(astRoot);
        moduleBody.addChildrenToFront(astRoot.removeChildren());
        astRoot.addChildToFront(moduleBody);
      }
      for (Map.Entry<JSModule, Set<String>> jsModuleImportsFromChunk :
          jsModuleImports.getValue().entrySet()) {
        Node importSpecs = new Node(Token.IMPORT_SPECS);
        for (String name : jsModuleImportsFromChunk.getValue()) {
          Node importSpec = new Node(Token.IMPORT_SPEC);
          importSpec.addChildToFront(IR.name(name));
          importSpec.addChildToFront(IR.name(name));
          importSpec.putIntProp(Node.IS_SHORTHAND_PROPERTY, 1);
          importSpecs.addChildToBack(importSpec);
        }
        Node importStatement = new Node(Token.IMPORT);
        String importPath =
            this.resolveSibling(
                jsModuleImports.getKey().getName() + ".js",
                jsModuleImportsFromChunk.getKey().getName() + ".js");
        if (!importPath.startsWith(".") && !importPath.startsWith("/")) {
          importPath = "./" + importPath;
        }
        importStatement.addChildToFront(IR.string(importPath));
        importStatement.addChildToFront(importSpecs);
        importStatement.addChildToFront(IR.empty());
        importStatement.useSourceInfoFromForTree(astRoot);
        importStatements.add(0, importStatement);
      }
      for (Node importStatement : importStatements) {
        moduleBody.addChildToFront(importStatement);
      }
      compiler.reportChangeToChangeScope(astRoot);
    }
  }

  /** Find names in a module that are defined in a different module. */
  private class FindCrossChunkReferences extends AbstractPostOrderCallback {
    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      if (n.isName()) {
        String name = n.getString();
        if ("".equals(name)) {
          return;
        }
        Scope s = t.getScope();
        Var v = s.getVar(name);
        if (v == null || !v.isGlobal()) {
          return;
        }
        CompilerInput input = v.getInput();
        if (input == null) {
          return;
        }
        // Compare the chunk where the variable is declared to the current
        // chunk. If they are different, the variable is used across modules.
        JSModule module = input.getModule();
        if (module != t.getModule()) {
          if (NodeUtil.isLhsOfAssign(n)) {
            t.report(
                n,
                ASSIGNMENT_TO_IMPORT,
                n.getString(),
                t.getModule().getName());
          }

          // Mark the chunk where the name is declared as needing an export for this name
          Set<String> namesToExport = crossChunkExports.get(module);
          if (namesToExport == null) {
            namesToExport = new HashSet<>();
            crossChunkExports.put(module, namesToExport);
          }
          namesToExport.add(name);

          // Add an import for this name to this module from the source module
          Map<JSModule, Set<String>> namesToImportByModule = crossChunkImports.get(t.getModule());
          if (namesToImportByModule == null) {
            namesToImportByModule = new HashMap<>();
            crossChunkImports.put(t.getModule(), namesToImportByModule);
          }
          Set<String> importsForModule = namesToImportByModule.get(module);
          if (importsForModule == null) {
            importsForModule = new HashSet<>();
            namesToImportByModule.put(module, importsForModule);
          }
          importsForModule.add(name);
        }
      }
    }
  }

  private String resolveSibling(String fromPath, String toPath) {
    // output chunks are likely to be in the same folder. Specialize for that case.
    List<String> fromPathParts = new ArrayList<>(Arrays.asList(fromPath.split("/")));
    List<String> toPathParts = new ArrayList<>(Arrays.asList(toPath.split("/")));
    if (fromPathParts.size() == toPathParts.size() &&
        !(fromPathParts.get(0).equals(".") || fromPathParts.get(0).equals("..")) &&
        !(toPathParts.get(0).equals(".") || toPathParts.get(0).equals(".."))) {
      boolean equalFolderPaths = true;
      for (int i = 0; i < fromPathParts.size() - 1; i++) {
        if (!fromPathParts.get(i).equals(toPathParts.get(i))) {
          equalFolderPaths = false;
          break;
        }
      }
      if (equalFolderPaths) {
        return "./" + toPathParts.get(toPathParts.size() - 1);
      }
    }

    return Compiler.resolveSibling(fromPath, toPath);
  }
}
