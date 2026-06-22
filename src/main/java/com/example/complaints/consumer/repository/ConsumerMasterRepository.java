package com.example.complaints.consumer.repository;

import com.example.complaints.consumer.model.ConsumerMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConsumerMasterRepository extends JpaRepository<ConsumerMaster, Long> {

    Optional<ConsumerMaster> findByConsumerId(String consumerId);
}

