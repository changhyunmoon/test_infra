import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

export const options = {
  scenarios: {
    monitoring_smoke: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 5),
      duration: __ENV.DURATION || '1m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000'],
    monitoring_create_duration: ['p(95)<1000'],
    monitoring_count_duration: ['p(95)<500'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://3.37.158.87:8080/api';
const TEST_RUN_ID = __ENV.TEST_RUN_ID || `monitoring-${Date.now()}`;
const createDuration = new Trend('monitoring_create_duration');
const countDuration = new Trend('monitoring_count_duration');
const apiSuccess = new Rate('monitoring_api_success');

export default function () {
  const headers = {
    'Content-Type': 'application/json',
    'X-Test-Run-Id': TEST_RUN_ID,
    'X-Scenario': 'monitoring-jpa-test',
  };

  const createPayload = JSON.stringify({
    name: `k6-${TEST_RUN_ID}-${__VU}-${__ITER}`,
  });

  const createRes = http.post(`${BASE_URL}/monitoring/tests`, createPayload, {
    headers,
    tags: {
      api: 'monitoring-create-test',
    },
  });

  createDuration.add(createRes.timings.duration);
  apiSuccess.add(createRes.status === 201);

  check(createRes, {
    'create status is 201': (res) => res.status === 201,
    'create response has id': (res) => {
      try {
        return Boolean(res.json('id'));
      } catch (error) {
        return false;
      }
    },
  });

  const countRes = http.get(`${BASE_URL}/monitoring/tests/count`, {
    headers,
    tags: {
      api: 'monitoring-count-test',
    },
  });

  countDuration.add(countRes.timings.duration);
  apiSuccess.add(countRes.status === 200);

  check(countRes, {
    'count status is 200': (res) => res.status === 200,
    'count response has count': (res) => {
      try {
        return typeof res.json('count') === 'number';
      } catch (error) {
        return false;
      }
    },
  });

  sleep(1);
}
