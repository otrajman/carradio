// Phonetic handle generator — word lists must stay identical to docs/handles.json.
const ADJECTIVES = [
  "Neon", "Crimson", "Silver", "Cobalt", "Amber", "Turbo", "Midnight", "Solar",
  "Electric", "Copper", "Ivory", "Onyx", "Scarlet", "Golden", "Azure", "Emerald",
  "Velvet", "Chrome", "Shadow", "Blazing", "Frost", "Thunder", "Drift", "Radiant",
  "Lucky", "Rusty", "Swift", "Quiet", "Wild", "Nova", "Retro", "Phantom",
];
const ANIMALS = [
  "Falcon", "Otter", "Lynx", "Bison", "Coyote", "Heron", "Marlin", "Puma",
  "Raven", "Stallion", "Badger", "Condor", "Dingo", "Elk", "Fox", "Gazelle",
  "Hawk", "Ibex", "Jaguar", "Kestrel", "Llama", "Moose", "Narwhal", "Osprey",
  "Panther", "Quail", "Rhino", "Sparrow", "Tiger", "Viper", "Wolf", "Wombat",
];

export function generateHandle(rand: () => number = Math.random): string {
  const a = ADJECTIVES[Math.floor(rand() * ADJECTIVES.length)];
  const b = ANIMALS[Math.floor(rand() * ANIMALS.length)];
  return `${a} ${b}`;
}
