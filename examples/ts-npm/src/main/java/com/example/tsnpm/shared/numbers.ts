// Used by both client modules, so esbuild puts it in a shared chunk that j2act serves with each.
export const total = (values: number[]): number => values.reduce((sum, value) => sum + value, 0);
