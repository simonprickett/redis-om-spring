package com.redislabs.spring;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.CachingConfigurerSupport;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisKeyValueTemplate;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.mapping.RedisMappingContext;

import com.redislabs.spring.annotations.Document;
import com.redislabs.spring.annotations.TagIndexed;
import com.redislabs.spring.annotations.TextIndexed;
import com.redislabs.spring.client.RedisModulesClient;
import com.redislabs.spring.ops.RedisModulesOperations;
import com.redislabs.spring.ops.json.JSONOperations;
import com.redislabs.spring.ops.search.SearchOperations;

import io.redisearch.FieldName;
import io.redisearch.Schema;
import io.redisearch.Schema.Field;
import io.redisearch.Schema.FieldType;
import io.redisearch.Schema.TextField;
import io.redisearch.client.Client;
import io.redisearch.client.IndexDefinition;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisProperties.class)
public class RedisModulesConfiguration extends CachingConfigurerSupport {

  @Bean(name = "redisModulesClient")
  RedisModulesClient redisModulesClient(JedisConnectionFactory jedisConnectionFactory) {
    return new RedisModulesClient(jedisConnectionFactory);
  }

  @Bean(name = "redisModulesOperations")
  RedisModulesOperations<?, ?> redisModulesOperations(RedisModulesClient rmc) {
    return new RedisModulesOperations<>(rmc);
  }

  @Bean(name = "redisJSONOperations")
  JSONOperations<?> redisJSONOperations(RedisModulesOperations<?, ?> redisModulesOperations) {
    return redisModulesOperations.opsForJSON();
  }

  @Bean(name = "redisTemplate")
  @Primary
  public RedisTemplate<?, ?> redisTemplate(JedisConnectionFactory connectionFactory) {
    RedisTemplate<?, ?> template = new RedisTemplate<>();
    template.setConnectionFactory(connectionFactory);

    return template;
  }

  @Bean(name = "redisJSONKeyValueAdapter")
  RedisJSONKeyValueAdapter getRedisJSONKeyValueAdapter(RedisOperations<?, ?> redisOps,
      JSONOperations<?> redisJSONOperations) {
    return new RedisJSONKeyValueAdapter(redisOps, redisJSONOperations);
  }

  @Bean(name = "redisKeyValueTemplate")
  public RedisKeyValueTemplate getRedisJSONKeyValueTemplate(RedisOperations<?, ?> redisOps,
      JSONOperations<?> redisJSONOperations) {
    RedisMappingContext mappingContext = new RedisMappingContext();
    return new RedisKeyValueTemplate(getRedisJSONKeyValueAdapter(redisOps, redisJSONOperations), mappingContext);
  }

  @EventListener(ContextRefreshedEvent.class)
  public void ensureIndexesAreCreated(ContextRefreshedEvent cre) {
    System.out.println(">>>> On ContextRefreshedEvent ... Creating Indexes......");
    
    ApplicationContext ac = cre.getApplicationContext();
    @SuppressWarnings("unchecked")
    RedisModulesOperations<String, String> rmo = (RedisModulesOperations<String, String>) ac.getBean("redisModulesOperations");

    ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(false);
    provider.addIncludeFilter(new AnnotationTypeFilter(Document.class));
    for (BeanDefinition beanDef : provider
        .findCandidateComponents("com.redislabs.spring.annotations.document.fixtures")) {
      try {
        Class<?> cl = Class.forName(beanDef.getBeanClassName());
        //Document document = cl.getAnnotation(Document.class);
        System.out.printf(">>>> Found @Document annotated class: %s\n", cl.getSimpleName());
        
        
        List<Field> fields = new ArrayList<Field>();
        for (java.lang.reflect.Field field : cl.getDeclaredFields()) {
          System.out.println(">>>> Inspecting field " + field.getName());
          // Text
          if (field.isAnnotationPresent(TextIndexed.class)) {
            System.out.println(">>>>>> FOUND TextIndexed on " + field.getName());
            TextIndexed ti = field.getAnnotation(TextIndexed.class);
            
            FieldName fieldName = FieldName.of("$." + field.getName());
            if (!ObjectUtils.isEmpty(ti.alias())) {
              fieldName = fieldName.as(ti.alias());
            }
            String phonetic = ObjectUtils.isEmpty(ti.phonetic()) ? null : ti.phonetic();
            TextField tf = new TextField(fieldName, ti.weight(), ti.sortable(), ti.nostem(), ti.noindex(), phonetic);
           
            fields.add(tf);
          }
          // Tag
          if (field.isAnnotationPresent(TagIndexed.class)) {
            System.out.println(">>>>>> FOUND TagIndexed on " + field.getName());
            TagIndexed ti = field.getAnnotation(TagIndexed.class);
            
            FieldName fieldName = FieldName.of("$." + field.getName() + "[*]");
            if (!ObjectUtils.isEmpty(ti.alias())) {
              fieldName = fieldName.as(ti.alias());
            }
            Field tf = new Field(fieldName, FieldType.Tag, ti.sortable(), ti.noindex());
           
            fields.add(tf);
          }
        }
        
        if (!fields.isEmpty()) {
          Schema schema = new Schema();
          SearchOperations<String> opsForSearch = rmo.opsForSearch(cl.getSimpleName() + "Idx");
          for (Field field : fields) {
            schema.addField(field);
          }
          IndexDefinition def = new IndexDefinition(IndexDefinition.Type.JSON);
          opsForSearch.createIndex(schema, Client.IndexOptions.defaultOptions().setDefinition(def));
        }
      } catch (Exception e) {
        System.err.println("Got exception: " + e.getMessage());
      }
    }

  }
}