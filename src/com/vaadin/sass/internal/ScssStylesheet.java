/*
 * Copyright 2011 Vaadin Ltd.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.vaadin.sass.internal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.logging.Logger;

import org.w3c.css.sac.CSSException;
import org.w3c.css.sac.InputSource;

import com.vaadin.sass.internal.handler.SCSSDocumentHandler;
import com.vaadin.sass.internal.handler.SCSSDocumentHandlerImpl;
import com.vaadin.sass.internal.handler.SCSSErrorHandler;
import com.vaadin.sass.internal.parser.Parser;
import com.vaadin.sass.internal.resolver.ScssStylesheetResolver;
import com.vaadin.sass.internal.resolver.VaadinResolver;
import com.vaadin.sass.internal.tree.BlockNode;
import com.vaadin.sass.internal.tree.MixinDefNode;
import com.vaadin.sass.internal.tree.Node;
import com.vaadin.sass.internal.tree.VariableNode;
import com.vaadin.sass.internal.tree.controldirective.IfElseDefNode;
import com.vaadin.sass.internal.visitor.ImportNodeHandler;

public class ScssStylesheet extends Node {

    private static final long serialVersionUID = 3849790204404961608L;

    private static ScssStylesheet mainStyleSheet = null;

    private static final HashMap<String, VariableNode> variables = new HashMap<String, VariableNode>();

    private static final Map<String, MixinDefNode> mixinDefs = new HashMap<String, MixinDefNode>();

    private static final HashSet<IfElseDefNode> ifElseDefNodes = new HashSet<IfElseDefNode>();

    private static HashMap<Node, Node> lastNodeAdded = new HashMap<Node, Node>();

    private String fileName;

    /**
     * Read in a file SCSS and parse it into a ScssStylesheet
     * 
     * @param file
     * @throws IOException
     */
    public ScssStylesheet() {
        super();
    }

    /**
     * Main entry point for the SASS compiler. Takes in a file and builds upp a
     * ScssStylesheet tree out of it. Calling compile() on it will transform
     * SASS into CSS. Calling toString() will print out the SCSS/CSS.
     * 
     * @param file
     * @return
     * @throws CSSException
     * @throws IOException
     */
    public static ScssStylesheet get(String identifier) throws CSSException,
            IOException {
        File file = new File(identifier);
        file = file.getCanonicalFile();

        SCSSDocumentHandler handler = new SCSSDocumentHandlerImpl();
        ScssStylesheet stylesheet = handler.getStyleSheet();

        InputSource source = stylesheet.resolveStylesheet(identifier);
        if (source == null) {
            return null;
        }

        Parser parser = new Parser();
        parser.setErrorHandler(new SCSSErrorHandler());
        parser.setDocumentHandler(handler);
        parser.parseStyleSheet(source);

        return stylesheet;
    }

    private static ScssStylesheetResolver[] resolvers = null;

    public static void setStylesheetResolvers(
            ScssStylesheetResolver... styleSheetResolvers) {
        resolvers = Arrays.copyOf(styleSheetResolvers,
                styleSheetResolvers.length);
    }

    public InputSource resolveStylesheet(String identifier) {
        if (resolvers == null) {
            setStylesheetResolvers(new VaadinResolver());
        }

        for (ScssStylesheetResolver resolver : resolvers) {
            InputSource source = resolver.resolve(identifier);
            if (source != null) {
                File f = new File(source.getURI());
                setFileName(f.getParent());
                return source;
            }
        }

        return null;
    }

    /**
     * Applies all the visitors and compiles SCSS into Css.
     * 
     * @throws Exception
     */
    public void compile() throws Exception {
        mainStyleSheet = this;
        mixinDefs.clear();
        variables.clear();
        ifElseDefNodes.clear();
        lastNodeAdded.clear();
        importOtherFiles(this);
        populateDefinitions(this);
        traverse(this);
        removeEmptyBlocks(this);
    }

    private void importOtherFiles(ScssStylesheet node) {
        ImportNodeHandler.traverse(node);
    }

    private void populateDefinitions(Node node) {
        if (node instanceof MixinDefNode) {
            mixinDefs.put(((MixinDefNode) node).getName(), (MixinDefNode) node);
            node.getParentNode().removeChild(node);
        } else if (node instanceof IfElseDefNode) {
            ifElseDefNodes.add((IfElseDefNode) node);
        }

        for (final Node child : new ArrayList<Node>(node.getChildren())) {
            populateDefinitions(child);
        }

    }

    /**
     * Prints out the current state of the node tree. Will return SCSS before
     * compile and CSS after.
     * 
     * For now this is an own method with it's own implementation that most node
     * types will implement themselves.
     */
    @Override
    public String toString() {
        StringBuilder string = new StringBuilder("");
        if (children.size() > 0) {
            string.append(children.get(0).toString());
        }
        String delimeter = "\n\n";
        if (children.size() > 1) {
            for (int i = 1; i < children.size(); i++) {
                String childString = children.get(i).toString();
                if (childString != null) {
                    string.append(delimeter).append(childString);
                }
            }
        }
        String output = string.toString();
        return output;
    }

    public void addChild(int index, VariableNode node) {
        if (node != null) {
            children.add(index, node);
        }
    }

    public static ScssStylesheet get() {
        return mainStyleSheet;
    }

    @Override
    public void traverse() {
        // Not used for ScssStylesheet
    }

    /**
     * Traverses a node and its children recursively, calling all the
     * appropriate handlers via {@link Node#traverse()}.
     * 
     * The node itself may be removed during the traversal and replaced with
     * other nodes at the same position or later on the child list of its
     * parent.
     * 
     * @param node
     *            node to traverse
     * @return true if the node was removed (and possibly replaced by others),
     *         false if not
     */
    public boolean traverse(Node node) {
        Node originalParent = node.getParentNode();

        node.traverse();

        @SuppressWarnings("unchecked")
        HashMap<String, VariableNode> variableScope = (HashMap<String, VariableNode>) variables
                .clone();

        // the size of the child list may change on each iteration: current node
        // may get deleted and possibly other nodes have been inserted where it
        // was or after that position
        for (int i = 0; i < node.getChildren().size(); i++) {
            Node current = node.getChildren().get(i);
            if (traverse(current)) {
                // current has been removed
                --i;
            }
        }

        variables.clear();
        variables.putAll(variableScope);

        // clean up insert point so that processing of the next block will
        // insert after that block
        lastNodeAdded.remove(originalParent);

        // has the node been removed from its parent?
        if (originalParent != null) {
            boolean removed = !originalParent.getChildren().contains(node);
            return removed;
        } else {
            return false;
        }
    }

    public void removeEmptyBlocks(Node node) {
        // depth first for avoiding re-checking parents of removed nodes
        for (Node child : new ArrayList<Node>(node.getChildren())) {
            removeEmptyBlocks(child);
        }
        Node parent = node.getParentNode();
        if (node instanceof BlockNode && node.getChildren().isEmpty()
                && parent != null) {
            // remove empty block
            parent.removeChild(node);
        }
    }

    public static void addVariable(VariableNode node) {
        variables.put(node.getName(), node);
    }

    public static VariableNode getVariable(String string) {
        return variables.get(string);
    }

    public static ArrayList<VariableNode> getVariables() {
        return new ArrayList<VariableNode>(variables.values());
    }

    public static MixinDefNode getMixinDefinition(String name) {
        return mixinDefs.get(name);
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileName() {
        return fileName;
    }

    public static HashMap<Node, Node> getLastNodeAdded() {
        return lastNodeAdded;
    }

    public static final void warning(String msg) {
        Logger.getLogger(ScssStylesheet.class.getName()).warning(msg);
    }
}
