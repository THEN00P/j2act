# Spike: scoped stores on CDI and Spring

Status: done. The decision is ADR 0030. The sources were throwaway and are not in the repo; the code that matters is quoted below.

The question was whether one annotation from j2act-core, which has no runtime dependencies, can make a class a tab-scoped bean on both containers, and what happens to `@Transactional` when a store calls its own methods from a query.

## CDI on WildFly 29 (Weld 5.1.1)

An extension in its own jar under `WEB-INF/lib` registered the scope and added a context holding one instance. The app had `@TabScoped class Editor`, an unannotated `Plain` and a servlet probing them through `Instance<>` and `BeanManager`. WildFly 29 does not start on JDK 25, so it ran on JDK 21.

| Variant | Result |
|---|---|
| Plain annotation, registered with `BeforeBeanDiscovery.addScope(TabScoped.class, true, false)`, no beans.xml | CDI not enabled for the archive at all; the servlet's injection points stay null |
| The same, `bean-discovery-mode="annotated"` | Scope registered (`isNormalScope` true), but `Editor` is never discovered |
| The same, `bean-discovery-mode="all"` | Discovered; `Editor$Proxy$_$$_WeldClientProxy`, one instance per context |
| Annotation meta-annotated with `@NormalScope`, no `addScope`, no beans.xml | Discovered; the archive becomes an implicit bean archive |
| The same, `annotated` | Discovered |

So `addScope` declares a scope but does not make it bean-defining, while `@NormalScope` does, as the spec's list of bean-defining annotations says.

Transactions, checked through `TransactionSynchronizationRegistry.getTransactionStatus()` (0 active, 6 none) and an entity on `ExampleDS`:

| Call | Result |
|---|---|
| `@Transactional` method called through the proxy | 0 |
| The same method called from a lambda inside the bean | 6 |
| Called through `this::txStatus` | 6 |
| Called through `@Inject Editor self` from a lambda | 0 |
| `em.createQuery(...).getSingleResult()` with no transaction | works |
| `em.persist` with no transaction | `TransactionRequiredException` |
| `persist` inside a `@Transactional` method called through `self` | works |

## Without CDI on the classpath

A class annotated with the `@NormalScope`-carrying annotation, compiled with only the annotation's jar on the classpath, compiled without a warning under javac 25 (`--release 11 -Xlint:all`) and ECJ 3.46. At runtime `isAnnotationPresent` was true and the annotation reported only its three `java.lang.annotation` meta-annotations.

## Spring 6.2

The annotation also carried `@Component` and `@Scope(value = "j2act-tab", proxyMode = TARGET_CLASS)`. An `AnnotationConfigApplicationContext` with a `Scope` registered under `j2act-tab` (one map per current tab) and `scan("spike.app")`, with no CDI API at compile or run time:

- the plain `@TabScoped` class was found by component scanning;
- `getBean` returned `Editor$$SpringCGLIB$$0`;
- two calls in tab A hit one instance, and switching the current tab to B gave another;
- nothing was logged about the missing `@NormalScope`.
