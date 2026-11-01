import type { WorkstreamBacklog } from '../types.ts';
import { backlog as andf } from './andf.ts';
import { backlog as andx } from './andx.ts';
import { backlog as chat } from './chat.ts';
import { backlog as cloud } from './cloud.ts';
import { backlog as coach } from './coach.ts';
import { backlog as engine } from './engine.ts';
import { backlog as gov } from './gov.ts';
import { backlog as integ } from './integ.ts';
import { backlog as later } from './later.ts';
import { backlog as qa } from './qa.ts';
import { backlog as rel } from './rel.ts';
import { backlog as sec } from './sec.ts';
import { backlog as social } from './social.ts';

/** All workstreams, in Project display order. `LATER` is always last. */
export const BACKLOG: WorkstreamBacklog[] = [
  gov,
  cloud,
  social,
  chat,
  integ,
  engine,
  coach,
  andf,
  andx,
  qa,
  sec,
  rel,
  later,
];
