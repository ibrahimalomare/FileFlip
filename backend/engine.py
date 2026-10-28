"""Bounded, real conversion engine. All paths belong to one disposable job directory."""
from pathlib import Path
import io, json, re, shutil, subprocess, zipfile, warnings, os
from PIL import Image, ImageOps
from docx import Document
from docx.shared import Inches
from docx.enum.section import WD_ORIENT
from openpyxl import Workbook, load_workbook
from openpyxl.utils.cell import range_boundaries
import pdfplumber

MAX_BYTES = 50 * 1024 * 1024
MAX_PIXELS = 25_000_000
MAX_PAGES = 100
Image.MAX_IMAGE_PIXELS = MAX_PIXELS
warnings.simplefilter('error', Image.DecompressionBombWarning)
class ConversionError(Exception): pass

def run(args, timeout=90):
    try:
        result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    except FileNotFoundError:
        raise ConversionError(f'Required server component is missing: {Path(args[0]).name}')
    except subprocess.TimeoutExpired:
        raise ConversionError('Conversion exceeded its time limit. Try a smaller document.')
    if result.returncode:
        raise ConversionError('The document could not be processed. It may be damaged or password-protected.')
    return result.stdout

def detect(path):
    head = path.read_bytes()[:8]
    if head.startswith(b'%PDF-'): return 'pdf'
    if head.startswith(b'\x89PNG\r\n\x1a\n'): return 'png'
    if head.startswith(b'\xff\xd8\xff'): return 'jpg'
    if head == b'\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1':
        # OLE signatures alone cannot distinguish Word from Excel; verify streams.
        import olefile
        with olefile.OleFileIO(path) as ole:
            if ole.exists('EncryptedPackage'): raise ConversionError('Password-protected Office files are not supported. Unlock the file first.')
            if ole.exists('WordDocument'): return 'doc'
            if ole.exists('Workbook') or ole.exists('Book'): return 'xls'
        raise ConversionError('Unsupported OLE document.')
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            if sum(x.file_size for x in z.infolist()) > 150*1024*1024:
                raise ConversionError('Expanded document is too large.')
            names = z.namelist()
            if any('vbaProject' in x for x in names): raise ConversionError('Macro-enabled documents are not supported.')
            if 'word/document.xml' in names: return 'docx'
            if 'xl/workbook.xml' in names: return 'xlsx'
    raise ConversionError('Unsupported or invalid file contents; changing the extension will not convert a file.')

def pages(spec, count):
    if count > MAX_PAGES: raise ConversionError('Document exceeds the 100-page limit. Split it first.')
    if not spec.strip(): return list(range(count))
    result = []
    try:
        for part in spec.split(','):
            ends = part.strip().split('-')
            if len(ends) > 2: raise ValueError()
            a = int(ends[0]); b = int(ends[-1])
            if not 1 <= a <= b <= count: raise ValueError()
            result.extend(range(a-1,b))
    except ValueError: raise ConversionError('Invalid page selection. Use numbers such as 1,3-5 within the document.')
    return list(dict.fromkeys(result))

def office(src, ext, root):
    folder = root / ('office-' + ext); folder.mkdir(exist_ok=True)
    profile = root / 'lo-profile'
    # Fresh profile; macros disabled, external link updates disabled.
    user = profile / 'user'; user.mkdir(parents=True,exist_ok=True)
    (user/'registrymodifications.xcu').write_text('''<?xml version="1.0"?><oor:items xmlns:oor="http://openoffice.org/2001/registry"><item oor:path="/org.openoffice.Office.Common/Security/Scripting"><prop oor:name="MacroSecurityLevel" oor:op="fuse"><value>3</value></prop></item><item oor:path="/org.openoffice.Office.Calc/Content/Update"><prop oor:name="Link" oor:op="fuse"><value>0</value></prop></item></oor:items>''')
    filter_name={'doc':'doc:MS Word 97','docx':'docx:Office Open XML Text','xls':'xls:MS Excel 97','xlsx':'xlsx:Calc MS Excel 2007 XML'}.get(ext,ext)
    run([os.environ.get('FILEFLIP_SOFFICE','libreoffice'), '-env:UserInstallation='+profile.as_uri(), '--headless','--convert-to',filter_name,'--outdir',str(folder),str(src)])
    out=folder/(src.stem+'.'+ext)
    if not out.exists() or not out.stat().st_size: raise ConversionError('Office conversion failed. Unlock protected files and verify the document opens normally.')
    return out

def render_pdf(src, root, spec='', dpi=144):
    with pdfplumber.open(src) as pdf: selected=pages(spec,len(pdf.pages))
    out=[]
    for i in selected:
        prefix=root/f'page-{i+1}'
        run([os.environ.get('FILEFLIP_PDFTOPPM','pdftoppm'),'-f',str(i+1),'-l',str(i+1),'-scale-to','2400','-r',str(dpi),'-png','-singlefile',str(src),str(prefix)])
        out.append(prefix.with_suffix('.png'))
    return out

def ocr(image, lang='eng', tsv=False):
    if not re.fullmatch(r'[a-z]{3}(\+[a-z]{3})*',lang): raise ConversionError('Invalid OCR language code.')
    return run(['tesseract',str(image),'stdout','-l',lang,'--psm','6']+(['tsv'] if tsv else []),60).decode('utf-8')

def ocr_table(image,lang):
    """Conservative aligned-column heuristic; reject ordinary prose/single-column text."""
    import csv
    lines={}
    for w in csv.DictReader(io.StringIO(ocr(image,lang,True)),delimiter='\t'):
        if not w.get('text','').strip() or float(w.get('conf',-1))<35: continue
        key=(w['block_num'],w['par_num'],w['line_num'])
        lines.setdefault(key,[]).append(w)
    rows=[]; starts=[]
    for words in lines.values():
        words.sort(key=lambda w:int(w['left']))
        cells=[]; xs=[]; right=-1
        for w in words:
            left=int(w['left']); height=int(w['height'])
            if right<0 or left-right>max(25,height*1.7):
                cells.append(w['text']); xs.append(left)
            else: cells[-1]+=' '+w['text']
            right=left+int(w['width'])
        if len(cells)>=2: rows.append(cells); starts.append(xs)
    if len(rows)<2: return []
    n=max(set(map(len,rows)), key=lambda c:sum(len(r)==c for r in rows))
    candidates=[(r,x) for r,x in zip(rows,starts) if len(r)==n]
    if len(candidates)<2: return []
    base=candidates[0][1]
    aligned=[r for r,x in candidates if all(abs(a-b)<35 for a,b in zip(base,x))]
    return [aligned] if len(aligned)>=2 else []

def tables(src,kind,opts,root):
    if kind=='docx':
        return [[[c.text for c in row.cells] for row in t.rows] for t in Document(src).tables]
    if kind in ('png','jpg'): return ocr_table(src,opts.get('language','eng'))
    if kind=='pdf':
        result=[]
        with pdfplumber.open(src) as pdf:
            for i in pages(opts.get('pages',''),len(pdf.pages)):
                p=pdf.pages[i]; found=p.extract_tables()
                if not found and not (p.extract_text() or '').strip():
                    image=render_pdf(src,root,str(i+1))[0]
                    found=ocr_table(image,opts.get('language','eng'))
                result.extend(found)
        return result
    raise ConversionError('This input does not support table extraction.')

def selected_workbook(src,opts,root):
    wb=load_workbook(src,data_only=opts.get('target')=='docx')
    names=[x.strip() for x in opts.get('sheets','').split(',') if x.strip()]
    if names and any(n not in wb.sheetnames for n in names): raise ConversionError('A selected sheet does not exist. Use exact sheet names separated by commas.')
    for ws in list(wb):
        if names and ws.title not in names: wb.remove(ws); continue
        if ws.max_row*ws.max_column>100_000: raise ConversionError('Sheet exceeds 100,000 cells. Reduce the source workbook.')
        ws.sheet_state='visible'
        ws.page_setup.orientation=opts.get('orientation','portrait')
        ws.page_setup.paperSize=ws.PAPERSIZE_A4 if opts.get('pageSize','A4')=='A4' else ws.PAPERSIZE_LETTER
        ws.sheet_properties.pageSetUpPr.fitToPage=True
        ws.page_setup.fitToWidth=1; ws.page_setup.fitToHeight=0
        if opts.get('range'):
            try: range_boundaries(opts['range']); ws.print_area=opts['range']
            except ValueError: raise ConversionError('Invalid cell range. Use A1:F30.')
    out=root/'selected.xlsx'; wb.save(out); return out

def workbook_doc(src,out,opts):
    wb=load_workbook(src,data_only=True); doc=Document()
    section=doc.sections[0]
    section.page_width=Inches(8.5 if opts.get('pageSize')=='Letter' else 8.2677)
    section.page_height=Inches(11 if opts.get('pageSize')=='Letter' else 11.6929)
    if opts.get('orientation')=='landscape':
        section.orientation=WD_ORIENT.LANDSCAPE; section.page_width,section.page_height=section.page_height,section.page_width
    for si,ws in enumerate(wb):
        if si: doc.add_page_break()
        doc.add_heading(ws.title,1)
        ranges=[opts['range']] if opts.get('range') else ([str(r) for r in __import__('openpyxl').worksheet.print_settings.PrintArea.from_string(ws.print_area).ranges] if ws.print_area else [ws.calculate_dimension()])
        for area in ranges:
            a,b,c,d=range_boundaries(area)
            if (d-b+1)*(c-a+1)>10_000: raise ConversionError('Word export is limited to 10,000 cells. Select a smaller range.')
            table=doc.add_table(rows=0, cols=c-a+1); table.style='Table Grid'
            for row in ws.iter_rows(min_row=b,max_row=d,min_col=a,max_col=c,values_only=True):
                cells=table.add_row().cells
                for cell,v in zip(cells,row): cell.text='' if v is None else str(v)
    doc.save(out)

def convert(inputs,opts,root,progress=lambda s:None):
    target=opts.get('target'); output=root/('result.'+str(target))
    if target not in ('pdf','docx','xlsx','png','jpg'): raise ConversionError('Unsupported output format.')
    if not inputs or len(inputs)>20: raise ConversionError('Select between 1 and 20 files.')
    kinds=[detect(p) for p in inputs]
    if len(inputs)>1 and (not all(k in ('jpg','png') for k in kinds) or target not in ('docx','xlsx')):
        raise ConversionError('Use one document at a time. Multiple images are supported for Word and table extraction.')
    progress('Validating document')
    for i, (src, kind) in enumerate(zip(inputs, kinds)):
        if kind in ('png','jpg'):
            rotations=opts.get('rotations',[])
            angle=rotations[i] if i<len(rotations) else 0
            if angle not in (0,90,180,270): raise ConversionError('Invalid image rotation.')
            with Image.open(src) as im:
                if im.width*im.height>MAX_PIXELS: raise ConversionError('Image exceeds 25 megapixels.')
                normalized=root/f'normalized-input-{i}.png'
                ImageOps.exif_transpose(im).rotate(-angle,expand=True).save(normalized)
            inputs[i]=normalized; kinds[i]='png'
    notes=[]; preview=[]
    if target=='xlsx':
        wb=Workbook(); wb.remove(wb.active)
        for src,kind in zip(inputs,kinds):
            if kind=='doc': src=office(src,'docx',root); kind='docx'
            progress('Detecting tables / OCR')
            for table in tables(src,kind,opts,root):
                if len(table)<2 or max(map(len,table),default=0)<2: continue
                if sum(len(r) for r in table)>100_000: raise ConversionError('Extracted table is too large.')
                ws=wb.create_sheet(f'Table {len(wb.worksheets)+1}')
                for row in table:
                    # Store untrusted extracted values as literal text, never formulas.
                    ws.append([str(v or '') for v in row])
                    for cell in ws[ws.max_row]: cell.data_type='s'
                preview.append({'sheet':ws.title,'rows':[[str(c or '')[:200] for c in r[:10]] for r in table[:15]]})
        if not wb.worksheets: raise ConversionError('No usable table was found. Try a clearer aligned table; paragraphs and arbitrary layouts cannot reliably become spreadsheet cells.')
        wb.save(output); notes.append('Review extracted cells. OCR, merged cells and borderless tables may be inaccurate. Values are literal text, not inferred formulas.')
    elif target=='docx' and all(k in ('jpg','png') for k in kinds):
        doc=Document()
        for i,src in enumerate(inputs):
            if i: doc.add_page_break()
            progress(f'Processing image {i+1} of {len(inputs)}')
            if opts.get('imageMode','ocr')=='embed':
                with Image.open(src) as im:
                    im=ImageOps.exif_transpose(im); normalized=root/f'normalized-{i}.png'; im.save(normalized)
                doc.add_picture(str(normalized),width=Inches(6))
            else:
                text=ocr(src,opts.get('language','eng')).strip()
                if not text: raise ConversionError('No readable text was found in the image.')
                doc.add_paragraph(text)
        doc.save(output); notes.append('OCR output is editable text; verify recognition and reading order.')
    elif kinds[0] in ('doc','docx','xls','xlsx'):
        src=inputs[0]; kind=kinds[0]
        if kind in ('doc','xls'): src=office(src,'docx' if kind=='doc' else 'xlsx',root); kind+='x'
        if kind=='xlsx': src=selected_workbook(src,opts,root)
        if target=='docx' and kind=='xlsx':
            workbook_doc(src,output,opts); notes.append('Tables contain cached cell values; formulas, charts and Excel layout are not preserved. Recalculate and save in Excel first.')
        elif target in ('pdf','png','jpg'):
            progress('Rendering with LibreOffice')
            pdf=office(src,'pdf',root)
            if target=='pdf': shutil.copyfile(pdf,output)
            else: output=export_images(render_pdf(pdf,root,opts.get('pages','')),target,root,opts)
            notes.append('Fonts unavailable on the server are substituted. Complex Office layout may shift; check the result.')
        else: raise ConversionError('This conversion is not offered.')
    elif kinds[0]=='pdf' and target=='docx':
        doc=Document(); src=inputs[0]
        with pdfplumber.open(src) as pdf:
            for j,i in enumerate(pages(opts.get('pages',''),len(pdf.pages))):
                progress(f'Extracting page {i+1}')
                if j: doc.add_page_break()
                page=pdf.pages[i]; text=page.extract_text(layout=True) or ''
                if not text.strip(): text=ocr(render_pdf(src,root,str(i+1))[0],opts.get('language','eng'))
                if not text.strip(): raise ConversionError(f'No readable text on page {i+1}.')
                doc.add_paragraph(text)
        doc.save(output); notes.append('Editable text with page breaks; columns, graphics, fonts and exact positions are not reconstructed. OCR is used on pages without text.')
    elif kinds[0]=='pdf' and target in ('png','jpg'):
        output=export_images(render_pdf(inputs[0],root,opts.get('pages','')),target,root,opts)
    elif kinds[0] in ('png','jpg') and target in ('png','jpg'):
        output=export_images(inputs,target,root,opts)
    else: raise ConversionError('Use the Android local converter for this conversion.')
    if not output.exists() or not output.stat().st_size: raise ConversionError('No output was produced.')
    if output.stat().st_size>100*1024*1024: raise ConversionError('Output exceeds 100 MB. Select fewer pages.')
    return {'file':output.name,'size':output.stat().st_size,'warnings':notes,'preview':preview}

def export_images(images,target,root,opts):
    results=[]
    background=opts.get('background','#FFFFFF')
    if target=='jpg' and not re.fullmatch('#[0-9a-fA-F]{6}',background): raise ConversionError('Background must be a six-digit hex color.')
    for i,src in enumerate(images):
        out=root/f'export-{i+1}.{target}'
        with Image.open(src) as im:
            im=ImageOps.exif_transpose(im).convert('RGBA')
            if im.width*im.height>MAX_PIXELS: raise ConversionError('Image exceeds 25 megapixels.')
            if target=='jpg':
                bg=Image.new('RGBA',im.size,background); bg.alpha_composite(im); im=bg.convert('RGB')
            im.save(out,format='JPEG' if target=='jpg' else 'PNG',quality=92)
        results.append(out)
    if len(results)==1: return results[0]
    out=root/'result.zip'
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as z:
        for f in results: z.write(f,f.name)
    return out
