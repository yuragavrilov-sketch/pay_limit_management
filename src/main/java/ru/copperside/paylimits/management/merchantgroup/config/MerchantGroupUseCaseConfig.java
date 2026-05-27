package ru.copperside.paylimits.management.merchantgroup.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.copperside.paylimits.management.merchantgroup.application.MerchantGroupService;
import ru.copperside.paylimits.management.merchantgroup.application.port.out.MerchantGroupRepository;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class MerchantGroupUseCaseConfig {

    @Bean
    MerchantGroupService merchantGroupService(MerchantGroupRepository repository, Clock clock) {
        return new MerchantGroupService(repository, clock);
    }
}
