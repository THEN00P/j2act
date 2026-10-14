/**
 * Client modules and npm packages (ADR 0022, 0023): the import map, Vite builds, and the files
 * served from MODULE_PATH and PACKAGE_PATH, CommonJS ones wrapped as ES modules. Internal to j2act:
 * public only so the engine in package j2act can reach it, with no compatibility promise.
 */
package j2act.internal.modules;
