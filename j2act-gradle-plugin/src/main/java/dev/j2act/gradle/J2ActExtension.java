package dev.j2act.gradle;

import org.gradle.api.Action;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Nested;

/**
 * j2act { } in a build script: what a project keeps setting. Each setting defaults to a Gradle property of the same
 * name, for a single build (-Pj2act.enhance.classFolders=true), and what the script sets wins.
 */
public abstract class J2ActExtension {

  /** The enhancer (ADR 0036). */
  @Nested
  public abstract Enhance getEnhance();

  public void enhance(Action<? super Enhance> configure) {
    configure.execute(getEnhance());
  }

  public abstract static class Enhance {

    /**
     * Also enhance the class folders themselves, so tests, the app run from the build and an exploded WAR of the
     * build's classes run enhanced. What an IDE compiles into its own folders stays plain. Default false, or
     * j2act.enhance.classFolders.
     */
    public abstract Property<Boolean> getClassFolders();

    /** Package plain classes: the app runs the same, re-running whole views. Default false, or j2act.enhance.skip. */
    public abstract Property<Boolean> getSkip();

    /**
     * Packages, by prefix such as "com.example.legacy.", whose classes stay plain store classes: a store reads them
     * whole instead of field by field (ADR 0035, 0036).
     */
    public abstract org.gradle.api.provider.ListProperty<String> getTrackExcludes();
  }
}
