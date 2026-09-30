package com.yiwei.midplat.fusion;
import com.yiwei.midplat.model.ModelService;
import com.yiwei.midplat.prompt.PromptService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
@Component
public class FusionCatalogBootstrap implements ApplicationRunner {
 private final ModelService models;private final PromptService prompts;
 public FusionCatalogBootstrap(ModelService models,PromptService prompts){this.models=models;this.prompts=prompts;}
 @Override public void run(ApplicationArguments args){models.captureCatalogRevisions();prompts.captureCatalogRevisions();}
}
