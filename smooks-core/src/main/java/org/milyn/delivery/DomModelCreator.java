/*
	Milyn - Copyright (C) 2006 - 2010

	This library is free software; you can redistribute it and/or
	modify it under the terms of the GNU Lesser General Public
	License (version 2.1) as published by the Free Software
	Foundation.

	This library is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

	See the GNU Lesser General Public License for more details:
	http://www.gnu.org/licenses/lgpl.txt
*/
package org.milyn.delivery;

import org.milyn.SmooksException;
import org.milyn.cdr.SmooksResourceConfiguration;
import org.milyn.cdr.annotation.Config;
import org.milyn.container.ExecutionContext;
import org.milyn.delivery.dom.DOMVisitBefore;
import org.milyn.delivery.ordering.Producer;
import org.milyn.delivery.sax.*;
import org.milyn.util.CollectionsUtil;
import org.milyn.xml.DomUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.util.Set;
import java.util.Stack;

/**
 * DOM Node Model creator.
 * <p/>
 * Adds the visited element as a node model.
 *
 * <h2>Mixing DOM and SAX</h2>
 * When used with SAX filtering, this visitor will construct a DOM Fragment of the visited
 * element.  This allows DOM utilities to be used in a Streaming environment.
 * <p/>
 * When 1+ model are nested inside each other, outer model will never contain data from the
 * inner model i.e. the same fragments will never cooexist inside two model.
 * <p/>
 * Take the following message as an example:
 * <pre>
 * &lt;order id='332'&gt;
 *     &lt;header&gt;
 *         &lt;customer number="123"&gt;Joe&lt;/customer&gt;
 *     &lt;/header&gt;
 *     &lt;order-items&gt;
 *         &lt;order-item id='1'&gt;
 *             &lt;product&gt;1&lt;/product&gt;
 *             &lt;quantity&gt;2&lt;/quantity&gt;
 *             &lt;price&gt;8.80&lt;/price&gt;
 *         &lt;/order-item&gt;
 *         &lt;order-item id='2'&gt;
 *             &lt;product&gt;2&lt;/product&gt;
 *             &lt;quantity&gt;2&lt;/quantity&gt;
 *             &lt;price&gt;8.80&lt;/price&gt;
 *         &lt;/order-item&gt;
 *         &lt;order-item id='3'&gt;
 *             &lt;product&gt;3&lt;/product&gt;
 *             &lt;quantity&gt;2&lt;/quantity&gt;
 *             &lt;price&gt;8.80&lt;/price&gt;
 *         &lt;/order-item&gt;
 *    &lt;/order-items&gt;
 * &lt;/order&gt;
 * </pre>
 * The {@link DomModelCreator} can be configured to create model for the "order" and "order-item"
 * message fragments:
 * <pre>
 * &lt;resource-config selector="order,order-item"&gt;
 *     &lt;resource&gt;org.milyn.delivery.DomModelCreator&lt;/resource&gt;
 * &lt;/resource-config&gt;
 * </pre>
 * In this case, the "order" model will never contain "order-item" model data (order-item elements are nested
 * inside the order element).  The in memory model for the "order" will simply be:
 * <pre>
 * &lt;order id='332'&gt;
 *     &lt;header&gt;
 *         &lt;customer number="123"&gt;Joe&lt;/customer&gt;
 *     &lt;/header&gt;
 *     &lt;order-items /&gt;
 * &lt;/order&gt;
 * </pre>
 * Added to this is the fact that there will only ever be 0 or 1 "order-item" model in memory
 * at any given time, with each new "order-item" model overwriting the previous "order-item" model.
 * All this ensures that the memory footprint is kept to a minimum.
 *
 * @author <a href="mailto:tom.fennelly@gmail.com">tom.fennelly@gmail.com</a>
 */
public class DomModelCreator implements DOMVisitBefore, SAXVisitBefore, SAXVisitAfter, Producer {
    private DocumentBuilder documentBuilder;

    @Config
    private SmooksResourceConfiguration config;

    public DomModelCreator() throws ParserConfigurationException {
        documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    }

    public Set<String> getProducts() {
        return CollectionsUtil.toSet(config.getTargetElement());
    }

    public void visitBefore(Element element, ExecutionContext executionContext) throws SmooksException {
        addNodeModel(element, executionContext);
    }

    public void visitBefore(SAXElement element, ExecutionContext executionContext) throws SmooksException, IOException {
        // Push a new DOMCreator onto the DOMCreator stack and install it in the
        // Dynamic Vistor list in the SAX handler...
        pushCreator(new DOMCreator(), executionContext);
    }

    public void visitAfter(SAXElement element, ExecutionContext executionContext) throws SmooksException, IOException {
        // Pop the DOMCreator off the DOMCreator stack and uninstall it from the
        // Dynamic Vistor list in the SAX handler...
        popCreator(executionContext);
    }

    private void addNodeModel(Element element, ExecutionContext executionContext) {
        DOMModel nodeModel = DOMModel.getModel(executionContext);
        nodeModel.getModels().put(DomUtils.getName(element), element);
    }

    @SuppressWarnings("unchecked")
    private void pushCreator(DOMCreator domCreator, ExecutionContext executionContext) {
        Stack<DOMCreator> domCreatorStack = (Stack<DOMCreator>) executionContext.getAttribute(DOMCreator.class);

        if(domCreatorStack == null) {
            domCreatorStack = new Stack<DOMCreator>();
            executionContext.setAttribute(DOMCreator.class, domCreatorStack);
        } else if(!domCreatorStack.isEmpty()) {
            // We need to remove the current DOMCreator from the dynamic visitor list because
            // we want to stop nodes being added to it and instead, have them added to the new
            // DOM.  This prevents a single huge DOM being created for a huge message (being processed
            // via SAX) because it maintains a hierarchy of model. Inner model can represent collection
            // entry instances, with a single model for a single collection entry only being held in memory
            // at any point in time i.e. old ones are overwritten and so freed for GC.
            DynamicSAXElementVisitorList.removeDynamicVisitor(domCreatorStack.peek(), executionContext);
        }

        DynamicSAXElementVisitorList.addDynamicVisitor(domCreator, executionContext);
        domCreatorStack.push(domCreator);
    }

    @SuppressWarnings({ "unchecked", "WeakerAccess", "UnusedReturnValue" })
    public Document popCreator(ExecutionContext executionContext) {
        Stack<DOMCreator> domCreatorStack = (Stack<DOMCreator>) executionContext.getAttribute(DOMCreator.class);

        if(domCreatorStack == null) {
            throw new IllegalStateException("No DOM Creator Stack available.");
        } else {
            try {
                // Remove the current DOMCreators from the dynamic visitor list...
                if(!domCreatorStack.isEmpty()) {
                    DOMCreator removedCreator = domCreatorStack.pop();
                    DynamicSAXElementVisitorList.removeDynamicVisitor(removedCreator, executionContext);

                    return removedCreator.document;
                } else {
                    return null;
                }
            } finally {
                // Reinstate parent DOMCreators in the dynamic visitor list...
                if(!domCreatorStack.isEmpty()) {
                    DynamicSAXElementVisitorList.addDynamicVisitor(domCreatorStack.peek(), executionContext);
                }
            }
        }
    }

    private class DOMCreator implements SAXElementVisitor {

        private Document document;
        private Node currentNode;

        private DOMCreator() {
            document = documentBuilder.newDocument();
            currentNode = document;
        }

        public void visitBefore(SAXElement element, ExecutionContext executionContext) throws SmooksException, IOException {
            Element domElement = element.toDOMElement(document);

            if(currentNode == document) {
                addNodeModel(domElement, executionContext);
            }

            currentNode.appendChild(domElement);
            currentNode = domElement;
        }

        @SuppressWarnings("RedundantThrows")
        public void onChildText(SAXElement element, SAXText childText, ExecutionContext executionContext) throws SmooksException, IOException {
            if(currentNode == document) {
                // Just ignore for now...
                return;
            }

            if(childText.getText().trim().length() == 0) {
                // Ignore pure whitespace...
                return;
            }

            switch (childText.getType()) {
                case TEXT:
                    currentNode.appendChild(document.createTextNode(childText.getText()));
                    break;
                case CDATA:
                    currentNode.appendChild(document.createCDATASection(childText.getText()));
                    break;
                case COMMENT:
                    currentNode.appendChild(document.createComment(childText.getText()));
                    break;
                case ENTITY:
                    currentNode.appendChild(document.createTextNode(childText.getText()));
                    break;
            }
        }

        @SuppressWarnings("RedundantThrows")
        public void onChildElement(SAXElement element, SAXElement childElement, ExecutionContext executionContext) throws SmooksException, IOException {
        }

        public void visitAfter(SAXElement element, ExecutionContext executionContext) throws SmooksException, IOException {
            currentNode = currentNode.getParentNode();
        }
    }
}
