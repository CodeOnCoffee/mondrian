/*
// $Id$
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// Copyright (C) 2007-2011 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package mondrian.util;

import mondrian.olap.Util;
import mondrian.resource.MondrianResource;

import org.apache.log4j.Logger;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Pattern;

// Only in Java5 and above

/**
 * Implementation of {@link UtilCompatible} which runs in
 * JDK 1.5.
 *
 * <p>Prior to JDK 1.5, this class should never be loaded. Applications should
 * instantiate this class via {@link Class#forName(String)} or better, use
 * methods in {@link mondrian.olap.Util}, and not instantiate it at all.
 *
 * @author jhyde
 * @version $Id$
 * @since Feb 5, 2007
 */
public abstract class UtilCompatibleJdk15 implements UtilCompatible {
    private final static Logger LOGGER =
        Logger.getLogger(Util.class);

    /**
     * This generates a BigDecimal with a precision reflecting
     * the precision of the input double.
     *
     * @param d input double
     * @return BigDecimal
     */
    public BigDecimal makeBigDecimalFromDouble(double d) {
        return new BigDecimal(d, MathContext.DECIMAL64);
    }

    public String quotePattern(String s) {
        return Pattern.quote(s);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAnnotation(
        Method method, String annotationClassName, T defaultValue)
    {
        try {
            Class<? extends Annotation> annotationClass =
                (Class<? extends Annotation>)
                    Class.forName(annotationClassName);
            if (method.isAnnotationPresent(annotationClass)) {
                final Annotation annotation =
                    method.getAnnotation(annotationClass);
                final Method method1 =
                    annotation.getClass().getMethod("value");
                return (T) method1.invoke(annotation);
            }
        } catch (IllegalAccessException e) {
            return defaultValue;
        } catch (InvocationTargetException e) {
            return defaultValue;
        } catch (NoSuchMethodException e) {
            return defaultValue;
        } catch (ClassNotFoundException e) {
            return defaultValue;
        }
        return defaultValue;
    }

    public String generateUuidString() {
        return UUID.randomUUID().toString();
    }

    public <T> T compileScript(
        Class<T> iface,
        String script,
        String engineName)
    {
        throw new UnsupportedOperationException(
            "Scripting not supported until Java 1.6");
    }

    public <T> void threadLocalRemove(ThreadLocal<T> threadLocal) {
        threadLocal.remove();
    }

    public void cancelAndCloseStatement(Statement stmt) {
        try {
            stmt.cancel();
        } catch (SQLException e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    MondrianResource.instance()
                        .ExecutionStatementCleanupException
                            .ex(e.getMessage(), e),
                    e);
            }
        }
        try {
            stmt.close();
        } catch (SQLException e) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    MondrianResource.instance()
                        .ExecutionStatementCleanupException
                            .ex(e.getMessage(), e),
                    e);
            }
        }
    }

    public <T> Set<T> newIdentityHashSet() {
        return Util.newIdentityHashSetFake();
    }
}

// End UtilCompatibleJdk15.java
