-- Fix: Create primary_sequence if it doesn't exist (for databases where V001 failed partially)
CREATE SEQUENCE IF NOT EXISTS primary_sequence START WITH 1 INCREMENT BY 1;
