// Demo routes for the simulator: real road geometry, simplified to polylines.
export interface Route {
  name: string;
  /** [lat, lng] waypoints, in driving order */
  points: [number, number][];
}

// I-90 (Mass Pike) eastbound approach into Boston — long straight-ish highway run.
export const MASS_PIKE_EAST: Route = {
  name: "I-90 East (Mass Pike)",
  points: [
    [42.3512, -71.2454],
    [42.3535, -71.2287],
    [42.3552, -71.2110],
    [42.3550, -71.1936],
    [42.3519, -71.1760],
    [42.3487, -71.1585],
    [42.3492, -71.1415],
    [42.3530, -71.1245],
    [42.3554, -71.1071],
    [42.3510, -71.0900],
    [42.3470, -71.0750],
    [42.3452, -71.0620],
  ],
};

// A downtown loop with turns — exercises heading changes.
export const DOWNTOWN_LOOP: Route = {
  name: "Downtown loop",
  points: [
    [42.3554, -71.0640],
    [42.3585, -71.0600],
    [42.3610, -71.0570],
    [42.3625, -71.0620],
    [42.3600, -71.0680],
    [42.3570, -71.0700],
    [42.3554, -71.0640],
  ],
};

export const ROUTES = [MASS_PIKE_EAST, DOWNTOWN_LOOP];
