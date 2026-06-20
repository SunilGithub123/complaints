-- =====================================================================
-- V1000.0__seed_dev_data.sql       (dev profile only)
-- =====================================================================
-- Loaded only when `spring.flyway.locations` includes `classpath:db/migration-dev`,
-- which is set in `application-dev.yml`. Production / test profiles never see this.
--
-- Provides the minimum master data needed for the bootstrap admin to start up
-- and for an end-to-end manual test: 1 subdivision, 2 DCs, 5 consumers.
--
-- Numbering V1000.x intentionally far ahead of the production migration sequence
-- so it never collides with real V1.x migrations.
-- =====================================================================

-- ---------- Subdivision ----------
INSERT INTO subdivision (code, name, district) VALUES
    ('SUB-NSK-001', 'Nashik Rural Subdivision', 'Nashik')
ON CONFLICT (code) DO NOTHING;

-- ---------- Distribution Centers ----------
INSERT INTO distribution_center (subdivision_id, code, name, address)
SELECT s.id, dc.code, dc.name, dc.address
FROM (VALUES
    ('DC-NSK-007', 'Sinnar Distribution Center',  'Sinnar Rd, Nashik'),
    ('DC-NSK-008', 'Igatpuri Distribution Center', 'Igatpuri, Nashik')
) AS dc(code, name, address)
CROSS JOIN subdivision s
WHERE s.code = 'SUB-NSK-001'
ON CONFLICT (code) DO NOTHING;

-- ---------- Consumer Master (5 fake consumers, mapped to DC-NSK-007) ----------
INSERT INTO consumer_master (consumer_id, name, mobile, email, address, distribution_center_id)
SELECT cm.consumer_id, cm.name, cm.mobile, cm.email, cm.address, dc.id
FROM (VALUES
    ('MH00010001', 'Ramesh Patil',   '+919900000001', 'ramesh@example.in',   'House 1, Sinnar'),
    ('MH00010002', 'Sunita Deshmukh','+919900000002', 'sunita@example.in',   'House 2, Sinnar'),
    ('MH00010003', 'Anil Kulkarni',  '+919900000003', 'anil@example.in',     'House 3, Sinnar'),
    ('MH00010004', 'Priya Joshi',    '+919900000004', 'priya@example.in',    'House 4, Sinnar'),
    ('MH00010005', 'Vikram Shinde',  '+919900000005', 'vikram@example.in',   'House 5, Sinnar')
) AS cm(consumer_id, name, mobile, email, address)
CROSS JOIN distribution_center dc
WHERE dc.code = 'DC-NSK-007'
ON CONFLICT (consumer_id) DO NOTHING;

