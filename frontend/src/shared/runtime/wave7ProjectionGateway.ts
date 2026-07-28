import { demoWave7ProjectionGateway } from '../../features/demo/wave7DemoGateway';
import {
  wave7ProjectionGateway as productionWave7ProjectionGateway,
  type Wave7ProjectionGateway,
} from '../../features/wave7/wave7Gateway';

export const wave7ProjectionGateway: Wave7ProjectionGateway = import.meta.env.MODE === 'demo'
  ? demoWave7ProjectionGateway
  : productionWave7ProjectionGateway;
