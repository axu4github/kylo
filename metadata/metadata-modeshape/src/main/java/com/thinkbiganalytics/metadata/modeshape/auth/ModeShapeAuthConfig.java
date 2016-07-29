/**
 * 
 */
package com.thinkbiganalytics.metadata.modeshape.auth;

import javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.jaas.AuthorityGranter;

import com.thinkbiganalytics.auth.jaas.JaasAuthConfig;
import com.thinkbiganalytics.auth.jaas.LoginConfiguration;
import com.thinkbiganalytics.auth.jaas.LoginConfigurationBuilder;

/**
 *
 * @author Sean Felten
 */
@Configuration
public class ModeShapeAuthConfig {
    
    @Bean
    public AuthorityGranter modeShapeAuthorityGranter()  {
        return new ModeShapeAuthorityGranter();
    }

    @Bean(name = "restModeShapeLoginConfiguration")
    public LoginConfiguration restModeShapeLoginConfiguration(LoginConfigurationBuilder builder) {
        return builder
                        .loginModule(JaasAuthConfig.JAAS_REST)
                            .moduleClass(ModeShapeLoginModule.class)
                            .controlFlag(LoginModuleControlFlag.REQUIRED)
                            .add()
                        .build();
    }
    
    @Bean(name = "uiModeShapeLoginConfiguration")
    public LoginConfiguration uiModeShapeLoginConfiguration(LoginConfigurationBuilder builder) {
        return builder
                        .loginModule(JaasAuthConfig.JAAS_UI)
                            .moduleClass(ModeShapeLoginModule.class)
                            .controlFlag(LoginModuleControlFlag.REQUIRED)
                            .add()
                        .build();
    }

}
