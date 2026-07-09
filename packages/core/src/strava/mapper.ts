import { Activity, type ActivityStreams, type SportType } from '@stride/schemas';

// Strava payloads are loosely typed; we validate on the way out via Activity.parse.
type Raw = Record<string, any>;

export function mapSportType(stravaType: string | undefined): SportType {
  switch (stravaType) {
    case 'Run':
    case 'VirtualRun':
      return stravaType === 'VirtualRun' ? 'treadmill_run' : 'run';
    case 'TrailRun':
      return 'trail_run';
    case 'Treadmill':
      return 'treadmill_run';
    case 'Walk':
      return 'walk';
    case 'Hike':
      return 'hike';
    default:
      return 'other';
  }
}

/** Map a Strava summary/detailed activity JSON object to a normalized Activity. */
export function mapActivity(raw: Raw, fetchedAt?: string): Activity {
  return Activity.parse({
    id: String(raw.id),
    source: 'strava',
    sportType: mapSportType(raw.sport_type ?? raw.type),
    name: raw.name ?? 'Untitled',
    startDate: raw.start_date,
    startDateLocal: raw.start_date_local,
    timezone: raw.timezone,
    distance: raw.distance ?? 0,
    movingTime: raw.moving_time ?? 0,
    elapsedTime: raw.elapsed_time ?? 0,
    totalElevationGain: raw.total_elevation_gain ?? 0,
    elevHigh: raw.elev_high,
    elevLow: raw.elev_low,
    averageSpeed: raw.average_speed,
    maxSpeed: raw.max_speed,
    averageHeartrate: raw.average_heartrate,
    maxHeartrate: raw.max_heartrate,
    hasHeartrate: raw.has_heartrate ?? false,
    averageCadence: raw.average_cadence,
    trainer: raw.trainer ?? false,
    manual: raw.manual ?? false,
    fetchedAt,
  });
}

/** Map a key_by_type=true streams response to normalized ActivityStreams. */
export function mapStreams(raw: Raw): ActivityStreams {
  const pick = (key: string): number[] | undefined => {
    const data = raw?.[key]?.data;
    return Array.isArray(data) ? data : undefined;
  };
  const streams: ActivityStreams = {};
  const time = pick('time');
  if (time) streams.time = time;
  const distance = pick('distance');
  if (distance) streams.distance = distance;
  const altitude = pick('altitude');
  if (altitude) streams.altitude = altitude;
  const velocity = pick('velocity_smooth');
  if (velocity) streams.velocitySmooth = velocity;
  const heartrate = pick('heartrate');
  if (heartrate) streams.heartrate = heartrate;
  const cadence = pick('cadence');
  if (cadence) streams.cadence = cadence;
  const watts = pick('watts');
  if (watts) streams.watts = watts;
  const grade = pick('grade_smooth');
  if (grade) streams.gradeSmooth = grade;
  const moving = raw?.moving?.data;
  if (Array.isArray(moving)) streams.moving = moving;
  return streams;
}
