# microBean Hikari Connection Pool CDI Integration

[![Build Status](https://travis-ci.org/microbean/microbean-datasource-cdi-hikaricp.svg?branch=master)](https://travis-ci.org/microbean/microbean-datasource-cdi-hikaricp)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.microbean/microbean-datasource-cdi-hikaricp/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.microbean/microbean-datasource-cdi-hikaricp)

The microBean Hikari Connection Pool CDI Integration project
integrates the [Hikari connection pool][hikaricp] into [CDI 2.0 SE
environments][cdi].

# Installation

To install the microBean Hikari Connection Pool CDI Integration
project, ensure that it and its dependencies are present on the
classpath at runtime.  In Maven, your dependency stanza should look
like this:

    <dependency>
      <groupId>org.microbean</groupId>
      <artifactId>microbean-datasource-cdi-hikaricp</artifactId>
      <!-- See http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.microbean%22%20AND%20a%3A%22microbean-datasource-cdi-hikaricp%22 for available releases. -->
      <version>0.1.1</version>
      <type>jar</type>
      <scope>runtime</scope>
    </dependency>
    
Releases are [available in Maven Central][maven-central].  Snapshots
are available in [Sonatype Snapshots][sonatype-snapshots].

You will also need a JDBC driver implementation on your classpath as
well at runtime.

# Usage

The microBean Hikari Connection Pool CDI Integration project works,
essentially, by satisfying `DataSource` injection points.  That is, in
your [CDI 2.0 SE][cdi] application somewhere, if you do:

    @Inject
    @Named("test")
    private DataSource ds;
    
...this project will arrange for a `DataSource` named `test` [backed
by the Hikari connection pool][hikari-datasource] to be assigned to
this `ds` field in [application scope][application-scope].  If you do:

    @Inject
    private DataSource orders;
    
...this project will arrange for a `DataSource` named `orders` [backed
by the Hikari connection pool][hikari-datasource] to be assigned to
this `orders` field in [application scope][application-scope].

For such injection points to be satisfied, the microBean Hikari
Connection Pool CDI Integration project needs to understand how to
configure any particular named `DataSource` implementation that will
be injected.  To do this, it looks for [`DataSourceDefinition`][dsd]
annotations and `datasource.properties` classpath resources.

Any type-level `DataSourceDefinition` annotations that are encountered
are processed.  Then `datasource.properties` [classpath
resources][classpath-resources] are sought and loaded, and their
properties are processed in the manner described below.

Properties in each `datasource.properties` file are inspected if they
start with either `javax.sql.DataSource.` or
`com.zaxxer.hikari.HikariDataSource.`, followed by a datasource name
that does not contain a period ('`.`'), followed by a period ('`.`'),
followed by the name of a [Hikari connection pool configuration
setting][hikaricp-config].

Properties are not read until all `datasource.properties` [classpath
resources][classpath-resources] have been effectively combined together.

`datasource.properties` [classpath resources][classpath-resources]
override [`DataSourceDefinition`][dsd] annotation values.

For each distinct data source name, a
[`HikariDataSource`][hikari-datasource] is created and made available
in [application scope][application-scope] with a [`Named`][named]
qualifier whose [value][named-value] is set to the data source name.
That `DataSource` implementation will then be configured by applying
all the relevant property suffixes after the first `.dataSource.`
string.  So, for example, the property
`javax.sql.DataSource.test.dataSource.url=jdbc:h2:mem:temp` will
result in a `HikariDataSource` implementation named `test`, with the
[Hikari connection pool setting][hikaricp-config] named
`dataSource.url` set to `jdbc:h2:mem:temp`.

Here is an example of a `datasource.properties` file that works with
the [in-memory variant of the H2 database][h2-mem]:

    javax.sql.DataSource.test.dataSourceClassName=org.h2.jdbcx.JdbcDataSource
    javax.sql.DataSource.test.dataSource.url=jdbc:h2:mem:test
    javax.sql.DataSource.test.username=sa
    javax.sql.DataSource.test.password=

Here is an example of a similar `datasource.properties` file that
declares two datasources:

    javax.sql.DataSource.test.dataSourceClassName=org.h2.jdbcx.JdbcDataSource
    javax.sql.DataSource.test.dataSource.url=jdbc:h2:mem:test
    javax.sql.DataSource.test.username=sa
    javax.sql.DataSource.test.password=
    
    javax.sql.DataSource.prod.dataSourceClassName=org.h2.jdbcx.JdbcDataSource
    javax.sql.DataSource.prod.dataSource.url=jdbc:h2:mem:test
    javax.sql.DataSource.prod.username=production
    javax.sql.DataSource.prod.password=s3kret

[hikaricp]: http://brettwooldridge.github.io/HikariCP/
[cdi]: http://docs.jboss.org/cdi/spec/2.0/cdi-spec.html#part_2
[maven-central]: http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22org.microbean%22%20AND%20a%3A%22microbean-datasource-cdi-hikaricp%22
[sonatype-snapshots]: https://oss.sonatype.org/content/repositories/snapshots/org/microbean/microbean-datasource-cdi-hikaricp/
[application-scope]: http://docs.jboss.org/cdi/spec/2.0/cdi-spec.html#application_context_se
[hikari-datasource]: https://static.javadoc.io/com.zaxxer/HikariCP/3.2.0/com/zaxxer/hikari/HikariDataSource.html
[dsd]: https://static.javadoc.io/javax/javaee-api/8.0/javax/annotation/sql/DataSourceDefinition.html
[classpath-resources]: https://docs.oracle.com/javase/8/docs/api/java/lang/ClassLoader.html#getResources-java.lang.String-
[hikaricp-config]: https://github.com/brettwooldridge/HikariCP/blob/dev/README.md#configuration-knobs-baby
[named]: https://static.javadoc.io/javax/javaee-api/8.0/javax/inject/Named.html
[named-value]: https://static.javadoc.io/javax/javaee-api/8.0/javax/inject/Named.html#value--
[h2-mem]: http://www.h2database.com/html/features.html#in_memory_databases
