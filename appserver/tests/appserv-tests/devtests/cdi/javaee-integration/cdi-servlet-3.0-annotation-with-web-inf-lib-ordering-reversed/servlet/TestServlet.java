/*
 * Copyright (c) 2010, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Enumeration;
import java.util.Set;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.inject.Inject;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.util.AnnotationLiteral;
import javax.naming.InitialContext;

@WebServlet(name="mytest",
        urlPatterns={"/myurl"},
        initParams={ @WebInitParam(name="n1", value="v1"), @WebInitParam(name="n2", value="v2") } )
public class TestServlet extends HttpServlet {
    @Inject TestBean tb;
    @Inject BeanManager bm;

    @Inject Foo f; //from WEB-INF/lib/alpha.jar
    @Inject Bar b; //from WEB-INF/lib/bravo.jar


    BeanManager bm1;
    
    @Inject 
    private transient org.jboss.logging.Logger log;

    public void service(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {

        PrintWriter writer = res.getWriter();
        writer.write("Hello from Servlet 3.0. ");
        String msg = "n1=" + getInitParameter("n1") +
            ", n2=" + getInitParameter("n2");
        
        //Bean Injection
        if (tb == null) msg += "Bean injection into Servlet failed";

        if (f == null) msg += "Bean injection of a TestBean(foo) in WEB-INF/lib/alpha.jar into Servlet failed";
        System.out.println("Test Bean foo from WEB-INF/lib/alpha.jar=" + f);

        if (b == null) msg += "Bean injection of a TestBean(bar) in WEB-INF/lib/bravo.jar into Servlet failed";
        System.out.println("Test Bean foo from WEB-INF/lib/bravo.jar=" + b);

        //BeanManager Injection
        System.out.println("BeanManager is " + bm);
        if (bm == null) msg += "BeanManager Injection via @Inject failed";

        try {
            bm1 = (BeanManager)((new InitialContext()).lookup("java:comp/BeanManager"));
            System.out.println("BeanManager via lookup is " + bm1);
        } catch (Exception ex) {
            ex.printStackTrace();
            msg += "BeanManager Injection via component environment lookup failed";
        }
        if (bm1 == null) msg += "BeanManager Injection via component environment lookup failed";

        //Check if Beans in WAR(WEB-INF/classes) and WEB-INF/lib/*.jar are visible
        //via BeanManager of WAR
        Set warBeans = bm.getBeans(TestBean.class,new AnnotationLiteral<Any>() {});
        if (warBeans.size() != 1) msg += "TestBean in WAR is not available via the WAR BeanManager";
        
        Set webinfLibBeans = bm.getBeans(Foo.class,new AnnotationLiteral<Any>() {});
        if (webinfLibBeans.size() != 1) msg += "TestBean Foo in WEB-INF/lib/alpha.jar is not available via the WAR BeanManager";
        System.out.println("Test Bean Foo from WEB-INF/lib/alpha.jar via BeanManager:" + webinfLibBeans);
        
        webinfLibBeans = bm.getBeans(Bar.class,new AnnotationLiteral<Any>() {});
        if (webinfLibBeans.size() != 1) msg += "TestBean Bar in WEB-INF/lib/bravo.jar is not available via the WAR BeanManager";
        System.out.println("Test Bean Bar from WEB-INF/lib/bravo.jar via BeanManager:" + webinfLibBeans);

        writer.write("initParams: " + msg + "\n");
    }
}
