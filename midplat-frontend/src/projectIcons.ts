export const PROJECT_ICONS = {
  'plat-qa': 'LineChart',
  'plat-kb': 'BookOpenText',
  'plat-an': 'Tags',
  'plat-root-cause': 'FileSearch',
  'plat-fishbone': 'GitFork',
  'plat-agent': 'BrainCircuit',
  'plat-data-integration': 'Database',
  'plat-smart-exploration': 'ScanLine',
  'plat-asset-catalog': 'Boxes',
  'plat-metadata': 'Layers',
  'plat-data-dev': 'Waypoints',
} as const;

export type ProjectId = keyof typeof PROJECT_ICONS;

export function projectIcon(projectId: ProjectId) {
  return PROJECT_ICONS[projectId];
}
