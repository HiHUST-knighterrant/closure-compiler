/*
 * Copyright 2010 The Closure Compiler Authors.
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

import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import java.util.HashMap;
import java.util.Map;

/**
 * Filters warnings based on in-code {@code @suppress} annotations.
 *
 * <p>Works by looking at the AST node associated with the warning, and looking at parents of the
 * node until it finds a node declaring a symbol (class, function, variable, property, assignment,
 * object literal key) or a script. For this reason, it doesn't work for warnings without an
 * associated AST node, eg, the ones in parsing/IRFactory. They can be turned off with jscomp_off.
 */
class SuppressDocWarningsGuard extends FileAwareWarningsGuard {
  private static final long serialVersionUID = 1L;

  /** Warnings guards for each suppressible warnings group, indexed by name. */
  private final Map<String, DiagnosticGroupWarningsGuard> suppressors =
       new HashMap<>();

  /** The suppressible groups, indexed by name. */
  SuppressDocWarningsGuard(
      AbstractCompiler compiler, Map<String, DiagnosticGroup> suppressibleGroups) {
    super(compiler);
    for (Map.Entry<String, DiagnosticGroup> entry : suppressibleGroups.entrySet()) {
      suppressors.put(
          entry.getKey(),
          new DiagnosticGroupWarningsGuard(
              entry.getValue(),
              CheckLevel.OFF));
    }

    // Hack: Allow "@suppress {missingProperties}" to mean
    // "@suppress {strictmissingProperties}".
    // TODO(johnlenz): Delete this when it is enabled with missingProperties
    suppressors.put(
        "missingProperties",
        new DiagnosticGroupWarningsGuard(
            new DiagnosticGroup(
                DiagnosticGroups.MISSING_PROPERTIES,
                DiagnosticGroups.STRICT_MISSING_PROPERTIES), CheckLevel.OFF));

    // Hack: Allow "@suppress {checkTypes}" to include
    // "strictmissingProperties".
    // TODO(johnlenz): Delete this when it is enabled with missingProperties
    suppressors.put(
        "checkTypes",
        new DiagnosticGroupWarningsGuard(
            new DiagnosticGroup(
                DiagnosticGroups.CHECK_TYPES,
                DiagnosticGroups.STRICT_CHECK_TYPES), CheckLevel.OFF));
  }

  @Override
  public CheckLevel level(JSError error) {
    Node node = error.getNode();
    if (node == null) {
      node = getScriptNodeForError(error);
    }
    if (node == null) {
      return null;
    }

    CheckLevel level = getCheckLevelFromAncestors(error, node);
    if (level != null) {
      return level;
    }

    // Some errors are on nodes that do not have the script as a parent.
    // Look up the script node by filename.
    Node scriptNode = getScriptNodeForError(error);
    if (scriptNode != null) {
      JSDocInfo info = scriptNode.getJSDocInfo();
      if (info != null) {
        return getCheckLevelFromInfo(error, info);
      }
    }

    return null;
  }

  /**
   * Searches for @suppress tags on nodes introducing symbols:
   *
   * <p>class & function declarations, variables, assignments, object literal keys, and the top
   * level script node.
   */
  private CheckLevel getCheckLevelFromAncestors(JSError error, Node node) {
    for (Node current = node; current != null; current = current.getParent()) {
      JSDocInfo info = null;
      if (current.isFunction() || current.isClass()) {
        info = NodeUtil.getBestJSDocInfo(current);
      } else if (current.isScript()) {
        info = current.getJSDocInfo();
      } else if (NodeUtil.isNameDeclaration(current)
          || NodeUtil.mayBeObjectLitKey(current)
          || current.isComputedProp()
          || ((NodeUtil.isAssignmentOp(current) || current.isGetProp())
              && current.hasParent()
              && current.getParent().isExprResult())) {
        info = NodeUtil.getBestJSDocInfo(current);
      }

      if (info != null) {
        CheckLevel level = getCheckLevelFromInfo(error, info);
        if (level != null) {
          return level;
        }
      }
    }

    return null;
  }

  /** If the given JSDocInfo has an @suppress for the given JSError, returns the new level. */
  private CheckLevel getCheckLevelFromInfo(JSError error, JSDocInfo info) {
    for (String suppressor : info.getSuppressions()) {
      WarningsGuard guard = suppressors.get(suppressor);

      // Some @suppress tags are for other tools, and
      // may not have a warnings guard.
      if (guard != null) {
        CheckLevel newLevel = guard.level(error);
        if (newLevel != null) {
          return newLevel;
        }
      }
    }
    return null;
  }

  @Override
  public int getPriority() {
    // Happens after path-based filtering, but before other times
    // of filtering.
    return WarningsGuard.Priority.SUPPRESS_DOC.value;
  }
}
