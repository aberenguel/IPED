/*
 * Copyright 2012-2014, Luis Filipe da Cruz Nassif
 * 
 * This file is part of Indexador e Processador de EvidÃªncias Digitais (IPED).
 *
 * IPED is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * IPED is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IPED.  If not, see <http://www.gnu.org/licenses/>.
 */
package dpf.sp.gpinf.indexer.process.task;

import gpinf.dev.data.EvidenceFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.text.Collator;
import java.util.HashMap;
import java.util.Properties;
import java.util.TreeSet;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import org.apache.tika.config.TikaConfig;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MediaTypeRegistry;

import dpf.sp.gpinf.indexer.Messages;
import dpf.sp.gpinf.indexer.process.Worker;

/**
 * Classe que carrega o mapeamento de mimeTypes para Categoria da aplicação. Além disso utiliza
 * regras javascript de definição de categorias baseadas nas propriedades dos itens. Também é
 * responsável por definir a categoria do item.
 */
public class SetCategoryTask extends AbstractTask {

  public static String CATEGORIES_BY_TYPE = "CategoriesByTypeConfig.txt"; //$NON-NLS-1$
  public static String CATEGORIES_BY_PROPS = "CategoriesByPropsConfig.txt"; //$NON-NLS-1$

  private static String FOLDER_CATEGORY = Messages.getString("SetCategoryTask.Folders"); //$NON-NLS-1$
  public static String SCANNED_CATEGORY = Messages.getString("SetCategoryTask.ScannedDocs"); //$NON-NLS-1$

  private static HashMap<String, String> mimetypeToCategoryMap = new HashMap<String, String>();
  private static TreeSet<String> categories;
  private static MediaTypeRegistry registry = TikaConfig.getDefaultConfig().getMediaTypeRegistry();
  private static ScriptEngine engine;
  private static Invocable inv;
  private static boolean refineCategories = false;

  public SetCategoryTask(Worker worker) {
    super(worker);
  }

  //TODO inserir parametro no init referente a arquivo e diretorio de configuraÃ§Ã£o	
  @Override
  public void init(Properties confProps, File configPath) throws Exception {
    load(new File(configPath, CATEGORIES_BY_TYPE));
    loadScript(new File(configPath, CATEGORIES_BY_PROPS));
  }

  @Override
  public void finish() throws Exception {
    // TODO Auto-generated method stub

  }

  public static synchronized void load(File file) throws FileNotFoundException, IOException {

    if (categories != null) {
      return;
    }

    categories = new TreeSet<String>(Collator.getInstance());
    categories.add(FOLDER_CATEGORY);
    categories.add(SCANNED_CATEGORY);

    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8")); //$NON-NLS-1$

    String line = reader.readLine();
    while ((line = reader.readLine()) != null) {
      if (line.startsWith("#")) { //$NON-NLS-1$
        continue;
      }
      String[] keyValuePair = line.split("="); //$NON-NLS-1$
      if (keyValuePair.length == 2) {
        String category = keyValuePair[0].trim();
        categories.add(category);
        String mimeTypes = keyValuePair[1].trim();
        for (String mimeType : mimeTypes.split(";")) { //$NON-NLS-1$
          mimeType = mimeType.trim();
          MediaType mt = MediaType.parse(mimeType);
          if(mt != null)
              mimeType = registry.normalize(mt).toString();
          mimetypeToCategoryMap.put(mimeType, category);
        }
      }
    }

    reader.close();

  }

  public static synchronized void loadScript(File file) throws FileNotFoundException, ScriptException, UnsupportedEncodingException, NoSuchMethodException {

    if (engine != null) {
      return;
    }

    InputStreamReader reader = new InputStreamReader(new FileInputStream(file), "UTF-8"); //$NON-NLS-1$

    ScriptEngineManager manager = new ScriptEngineManager();
    engine = manager.getEngineByName("javascript"); //$NON-NLS-1$
    engine.eval(reader);
    inv = (Invocable) engine;

    int size = categories.size();
    inv.invokeFunction("addNewCategories", categories); //$NON-NLS-1$
    if (categories.size() > size) {
      refineCategories = true;
    }

  }

  private static String get(MediaType type) {

    String category;
    do {
      category = mimetypeToCategoryMap.get(type.toString());
      if (category == null) {
        category = mimetypeToCategoryMap.get(type.getType());
      }
      if (category != null) {
        return category;
      }

      type = registry.getSupertype(type);

    } while (type != null);

    return ""; //$NON-NLS-1$
  }

  public static TreeSet<String> getCategories() {
    return categories;
  }

  public void process(EvidenceFile e) throws Exception {

    if (e.isDir()) {
      e.setCategory(FOLDER_CATEGORY);
    }

    if (e.getCategorySet().size() != 0) {
      return;
    }

    /*if (worker.containsReport && !ExportFileTask.hasCategoryToExtract()){
     //Categoria para itens exportados p/ report do FTK sem categoria, ex: anexos
     e.addCategory(Configuration.defaultCategory);
     return;
     }*/
    String category = get(e.getMediaType());
    e.addCategory(category);

    if (refineCategories) {
      inv.invokeFunction("addCategory", e); //$NON-NLS-1$
    }
  }

}
