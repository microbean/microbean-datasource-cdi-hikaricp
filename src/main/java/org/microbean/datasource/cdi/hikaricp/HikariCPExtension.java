/* -*- mode: Java; c-basic-offset: 2; indent-tabs-mode: nil; coding: utf-8-unix -*-
 *
 * Copyright © 2018 microBean.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.microbean.datasource.cdi.hikaricp;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

import java.lang.annotation.Annotation;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;
import java.lang.reflect.Parameter;

import java.net.URL;

import java.sql.Connection;

import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.sql.DataSourceDefinition;

import javax.enterprise.context.ApplicationScoped;

import javax.enterprise.event.Observes;

import javax.enterprise.inject.literal.NamedLiteral;

import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.Annotated;
import javax.enterprise.inject.spi.AnnotatedField;
import javax.enterprise.inject.spi.AnnotatedMember;
import javax.enterprise.inject.spi.AnnotatedParameter;
import javax.enterprise.inject.spi.CDI;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.ProcessInjectionPoint;
import javax.enterprise.inject.spi.WithAnnotations;

import javax.inject.Named;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * An {@link Extension} that arranges for injection points of type
 * {@link DataSource} qualified with {@link Named} to be satisfied
 * with {@link HikariDataSource} instances.
 *
 * @author <a href="https://about.me/lairdnelson"
 * target="_parent">Laird Nelson</a>
 *
 * @see HikariDataSource
 */
public class HikariCPExtension implements Extension {

  private static final Pattern dataSourceNamePattern = Pattern.compile("^(?:javax\\.sql\\.|com\\.zaxxer\\.hikari\\.Hikari)DataSource\\.([^.]+)\\.(.*)$");

  private final Map<String, Properties> masterProperties;

  /**
   * Creates a new {@link HikariCPExtension}.
   */
  public HikariCPExtension() {
    super();
    this.masterProperties = new HashMap<>();
  }

  private final void processAnnotatedType(@Observes @WithAnnotations(DataSourceDefinition.class) final ProcessAnnotatedType<?> event) throws IOException {
    if (event != null) {
      final Annotated annotated = event.getAnnotatedType();
      if (annotated != null) {
        final Set<DataSourceDefinition> dataSourceDefinitions = annotated.getAnnotations(DataSourceDefinition.class);
        if (dataSourceDefinitions != null && !dataSourceDefinitions.isEmpty()) {
          for (final DataSourceDefinition dsd : dataSourceDefinitions) {
            assert dsd != null;
            this.aggregateDataSourceProperties(toProperties(dsd));
          }
        }
      }
    }
  }

  private final void processInjectionPoint(@Observes final ProcessInjectionPoint<?, ? extends DataSource> event) {
    if (event != null) {
      final InjectionPoint ip = event.getInjectionPoint();
      if (ip != null) {
        final Set<Annotation> qualifiers = ip.getQualifiers();
        if (qualifiers != null) {
          boolean addQualifier = true;
          for (final Annotation qualifier : qualifiers) {
            if (qualifier != null && Named.class.equals(qualifier.annotationType())) {
              addQualifier = false;
              break;
            }
          }
          if (addQualifier) {
            final Named namedLiteral;
            final Annotated annotated = ip.getAnnotated();
            if (annotated instanceof AnnotatedField) {
              final Member field = ((AnnotatedField)annotated).getJavaMember();
              assert field != null;
              namedLiteral = NamedLiteral.of(field.getName());
            } else if (annotated instanceof AnnotatedParameter) {
              final AnnotatedParameter<?> annotatedParameter = (AnnotatedParameter<?>)annotated;

              final AnnotatedMember<?> annotatedMember = annotatedParameter.getDeclaringCallable();
              assert annotatedMember != null;
              
              final Member member = annotatedMember.getJavaMember();
              assert member != null;
              assert member instanceof Executable;
              
              final int parameterIndex = annotatedParameter.getPosition();
              assert parameterIndex >= 0;
              
              final Parameter[] parameters = ((Executable)member).getParameters();
              assert parameters != null;
              assert parameters.length >= parameterIndex;
              
              final Parameter parameter = parameters[parameterIndex];
              assert parameter != null;
              
              if (parameter.isNamePresent()) {
                namedLiteral = NamedLiteral.of(parameter.getName());
              } else {
                namedLiteral = null;
                throw new IllegalStateException("The parameter at index " +
                                                parameterIndex +
                                                " in " +
                                                member +
                                                " did not have a name available via reflection. " +
                                                "Make sure you compiled its enclosing class, " +
                                                member.getDeclaringClass().getName() +
                                                ", with the -parameters option supplied to javac, " +
                                                " or make use of the value() element of the " +
                                                Named.class.getName() +
                                                " annotation.");
              }
            } else {
              namedLiteral = null;
            }
            if (namedLiteral != null) {
              event.configureInjectionPoint()
                .addQualifier(namedLiteral);
            }
          }
        }
      }
    }
  }
  
  private final void afterBeanDiscovery(@Observes final AfterBeanDiscovery event) throws IOException {
    if (event != null) {
      final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
      assert tccl != null;
      final Enumeration<URL> dataSourcePropertiesUrls = tccl.getResources("datasource.properties");
      if (dataSourcePropertiesUrls != null && dataSourcePropertiesUrls.hasMoreElements()) {
        while (dataSourcePropertiesUrls.hasMoreElements()) {
          final URL dataSourcePropertiesUrl = dataSourcePropertiesUrls.nextElement();
          assert dataSourcePropertiesUrl != null;
          final Properties properties = new Properties();
          try (final InputStream inputStream = new BufferedInputStream(dataSourcePropertiesUrl.openStream())) {
            properties.load(inputStream);
          }
          this.aggregateDataSourceProperties(properties);
        }
      }
      if (!this.masterProperties.isEmpty()) {
        final Collection<? extends Entry<? extends String, ? extends Properties>> entries = this.masterProperties.entrySet();
        assert entries != null;
        assert !entries.isEmpty();
        for (final Entry<? extends String, ? extends Properties> entry : entries) {
          assert entry != null;
          final String dataSourceName = entry.getKey();
          assert dataSourceName != null;
          final Properties dataSourceProperties = entry.getValue();
          assert dataSourceProperties != null;
          this.addBean(event, dataSourceName, dataSourceProperties);
        }
        this.masterProperties.clear();
      }
    }
  }

  private static final Properties toProperties(final DataSourceDefinition dsd) {
    Objects.requireNonNull(dsd);
    final Properties returnValue = new Properties();
    final String dataSourceName = dsd.name();

    // See https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby.
    
    returnValue.setProperty(dataSourceName + ".dataSource.dataSourceClassName", dsd.className());

    // initialPoolSize -> (ignored)

    // maxStatements -> (ignored)

    // transactional -> (ignored)

    // minPoolSize -> minimumIdle 
    final int minPoolSize = dsd.minPoolSize();
    if (minPoolSize >= 0) {
      returnValue.setProperty(dataSourceName + ".dataSource.minimumIdle", String.valueOf(minPoolSize));
    }

    // maxPoolSize -> maximumPoolSize
    final int maxPoolSize = dsd.maxPoolSize();
    if (maxPoolSize >= 0) {
      returnValue.setProperty(dataSourceName + ".dataSource.maximumPoolSize", String.valueOf(maxPoolSize));
    }

    // loginTimeout -> connectionTimeout
    final int loginTimeout = dsd.loginTimeout();
    if (loginTimeout > 0) {
      returnValue.setProperty(dataSourceName + ".dataSource.connectionTimeout", String.valueOf(loginTimeout));
    }

    // maxIdleTime -> idleTimeout
    final int maxIdleTime = dsd.maxIdleTime();
    if (maxIdleTime >= 0) {
      returnValue.setProperty(dataSourceName + ".dataSource.idleTimeout", String.valueOf(maxIdleTime));
    }

    // password -> password
    //
    // Note: *not* dataSource.password
    final String password = dsd.password();
    assert password != null;
    if (!password.isEmpty()) {
      returnValue.setProperty(dataSourceName + ".dataSource.password", password);
    }

    // isolationLevel -> transactionIsolation
    final int isolationLevel = dsd.isolationLevel();    
    if (isolationLevel >= 0) {
      final String propertyValue;
      switch (isolationLevel) {
      case Connection.TRANSACTION_NONE:
        propertyValue = "TRANSACTION_NONE";
        break;
      case Connection.TRANSACTION_READ_UNCOMMITTED:
        propertyValue = "TRANSACTION_READ_UNCOMMITTED";
        break;
      case Connection.TRANSACTION_READ_COMMITTED:
        propertyValue = "TRANSACTION_READ_COMMITTED";
        break;
      case Connection.TRANSACTION_REPEATABLE_READ:
        propertyValue = "TRANSACTION_REPEATABLE_READ";
        break;
      case Connection.TRANSACTION_SERIALIZABLE:
        propertyValue = "TRANSACTION_SERIALIZABLE";
        break;
      default:
        propertyValue = null;
        throw new IllegalStateException("Unexpected isolation level: " + isolationLevel);
      }
      returnValue.setProperty(dataSourceName + ".dataSource.transactionIsolation", propertyValue);
    }
    
    // user -> dataSource.username
    //
    // This one's a bit odd.  Note that this does NOT map to
    // dataSource.user!
    final String user = dsd.user();
    assert user != null;
    if (!user.isEmpty()) {
      returnValue.setProperty(dataSourceName + ".dataSource.username", user);
    }
    
    // databaseName -> dataSource.databaseName (standard DataSource property)
    final String databaseName = dsd.databaseName();
    assert databaseName != null;
    if (!databaseName.isEmpty()) {
      returnValue.setProperty(dataSourceName + ".dataSource.dataSource.databaseName", databaseName);
    }

    // description -> dataSource.description (standard DataSource property)
    final String description = dsd.description();
    assert description != null;
    if (!description.isEmpty()) {
      returnValue.setProperty(dataSourceName + ".dataSource.dataSource.description", description);
    }

    // portNumber -> dataSource.portNumber (standard DataSource property)
    final int portNumber = dsd.portNumber();
    if (portNumber >= 0) {
      returnValue.setProperty(dataSourceName + ".dataSource.dataSource.portNumber", String.valueOf(portNumber));
    }
    
    // serverName -> dataSource.serverName (standard DataSource property)
    final String serverName = dsd.serverName();
    assert serverName != null;
    if (!serverName.isEmpty()) {
      returnValue.setProperty(dataSourceName + ".dataSource.dataSource.serverName", serverName);
    }   

    // url -> dataSource.url (standard DataSource property)
    final String url = dsd.url();
    assert url != null;
    if (!url.isEmpty()) {
      returnValue.setProperty(dataSourceName + ".dataSource.dataSource.url", url);
    }
    
    return returnValue;
  }
  
  private final void aggregateDataSourceProperties(final Properties dataSourceProperties) throws IOException {
    Objects.requireNonNull(dataSourceProperties);
    final Set<String> stringPropertyNames = dataSourceProperties.stringPropertyNames();
    if (stringPropertyNames != null && !stringPropertyNames.isEmpty()) {
      for (final String name : stringPropertyNames) {
        assert name != null;
        final Matcher matcher = dataSourceNamePattern.matcher(name);
        assert matcher != null;
        if (matcher.matches()) {
          final String dataSourceName = matcher.group(1);
          final String dataSourceProperty = matcher.group(2);
          Properties relevantProperties = this.masterProperties.get(dataSourceName);
          if (relevantProperties == null) {
            relevantProperties = new Properties();
            this.masterProperties.put(dataSourceName, relevantProperties);
          }
          relevantProperties.setProperty(dataSourceProperty, dataSourceProperties.getProperty(name));
        }        
      }
    }
  }

  private final void addBean(final AfterBeanDiscovery event, final String name, final Properties properties) {
    Objects.requireNonNull(event);
    Objects.requireNonNull(name);
    Objects.requireNonNull(properties);
    if (properties.getProperty("poolName") == null) {
      properties.setProperty("poolName", name);
    }    
    event.<HikariDataSource>addBean()
      .addQualifier(NamedLiteral.of(name))
      .addTransitiveTypeClosure(HikariDataSource.class)
      .beanClass(HikariDataSource.class)
      .scope(ApplicationScoped.class)
      .createWith(cc -> new HikariDataSource(new HikariConfig(properties)))
      .destroyWith((dataSource, cc) -> dataSource.close());
  }
  
}
