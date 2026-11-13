-- SYNTHETIC TEST DATA ONLY. Not a real business. Safe to re-run.
INSERT INTO business (id, code, name)
VALUES ('00000000-0000-4000-8000-000000000001', 'DEMO', 'Demo Produce Co (SYNTHETIC TEST DATA)')
ON CONFLICT (id) DO NOTHING;
