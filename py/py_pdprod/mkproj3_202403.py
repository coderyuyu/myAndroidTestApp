import os
# 2024.3.12
from ini import *
import sys
from os import listdir
from os.path import isfile, isdir, join
from PIL import Image
from PIL.ExifTags import TAGS
#from tinytag import TinyTag 
#import taglib
from hachoir.parser import createParser
from hachoir.metadata import extractMetadata
#from dateutil.parser import parse
from datetime import datetime, timedelta
#,date
import  time

maxdays=15
current_day = datetime.now() 
outfile = os.path.join(PDPROJ_PATH(),"16-9" + ".pdrproj")
if not os.path.isfile(outfile):
    newprojectfile(outfile)
            
img_ms=2000

class projcontrol:
	beginUS=0
	endUS=0
	timezone=8
	lastdt=0
	def __init__(self):
	       pass

	

pd = projcontrol



def sortFunction(value):
    #print('{a} {b}'.format(a=value['dt'],b='<--'))
    #print(value['file'])
    return value['media_dt']
    #return datetime.strptime(str(value['dt']),'%Y-%m-%d %H:%M:%S')


def cvt2mms(tstr):
    lista = tstr.split()
    h=0
    m=0
    s=0
    ms=0
    for i in lista:
        if str(i).isnumeric():
            v=int(i)
        else:
            if i == 'hour':
                h = v
            elif i == 'min':
                m = v
            elif i == 'sec':
                s = v
            elif i == 'ms':
                ms = v
            else:
                pass
    #return datetime.time(h,m,s,ms)
    return (h * 60 * 60 + m * 60 + s) * 1000 + ms

def cvt2dtstr(dstr):
    list = dstr.split()
    return str(list[0]).replace(':','-') + ' ' + str(list[1])

def get_exif(fullpath):
    filename, file_extension = os.path.splitext(fullpath)
    file_extension = file_extension.lower()
    media_dt=''
    media_type=''
    duration=0
    skip=0
    fname=os.path.basename(filename).lower()

    
    
    isdebug=0
    #print(fullpath)
    if file_extension == '.jpg' or file_extension == '.jpeg' :
        if fname.endswith("ios"):
            #print(fname)
            x = fname.split("_")
            media_dt = datetime.strptime(x[0]+x[1][:6],'%Y%m%d%H%M%S')
            media_type='i'
        elif fname.startswith("pxl"):
            #print(fname)
            x = fname.split("_")
            media_dt = datetime.strptime(x[1]+x[2][:6],'%Y%m%d%H%M%S')           
            media_type='i'
        else:
            img = Image.open(fullpath)
            # if '20201119_164625_120.953055555556_24.4680555555556_0_play____.jpg' in fullpath:
            #     print(fullpath)
            #     isdebug=1
            #     pass
            # 從檔名取得日期時間, 不果再由exif
            exif = img._getexif()
            if exif is not None:
                for (tag, value) in exif.items():
                    key = TAGS.get(tag, tag)             
                    # if isdebug==1:
                    #     print(key)
                    if key=='DateTime':
                        media_dt =  str(value)
                        tmp = cvt2dtstr(media_dt)  # + timedelta(hours=8)
                        media_dt = datetime.strptime(tmp,'%Y-%m-%d %H:%M:%S') + timedelta(hours=-1) #**** change
                        #datetime.strptime(tmp,'%Y-%m-%d %H:%M:%S') + timedelta(hours=8)
                        media_type='i'
                        break
                    elif key=='DateTimeOriginal' and media_dt=='':
                        media_dt =  str(value)
                        tmp = cvt2dtstr(media_dt)  # + timedelta(hours=8)
                        media_dt = datetime.strptime(tmp,'%Y-%m-%d %H:%M:%S') - timedelta(hours=1)    
                        #datetime.strptime(tmp,'%Y-%m-%d %H:%M:%S') + timedelta(hours=8)
                        media_type='i'               
                    try:
                        #print(str(key) + ' = ' + str(value))
                        pass
                    except:
                        #print("except:----------------" )
                        pass
            else:
                print('no exif data')      
            pass
    elif file_extension == '.mp3' or file_extension == '.m4a' :
        pass
        # audio = TinyTag.get(fullpath) 
        #print("Duration: " + str(audio.duration) + " seconds") 
    elif file_extension == '.mp4'  or file_extension == '.mov':
        

            #print(fullpath)
        try:
            parser = createParser(fullpath)
            with parser:
                metadata = extractMetadata(parser)
            
            eobj = metadata.exportDictionary()
            exif_dict = eobj['Metadata']
            if fname.endswith("ios"):
            #print(fname)
                x = fname.split("_")
                media_dt = datetime.strptime(x[0]+x[1][:6],'%Y%m%d%H%M%S')
            elif fname.startswith("pxl"):
            #print(fname)
                x = fname.split("_")
                media_dt = datetime.strptime(x[1]+x[2][:6],'%Y%m%d%H%M%S')  
            else: 
                tmp = cvt2dtstr(exif_dict['Creation date'])  # - timedelta(hours=7)
# if i japan, the hours should change to 9
                media_dt = datetime.strptime(tmp,'%Y-%m-%d %H:%M:%S') - timedelta(hours=0) #pd.timezone)
            duration = cvt2mms(exif_dict['Duration'])
            media_type='v'
        except:
            skip=1
    else:
        skip=1
    
    if (skip==0 and media_type != ''):
        print(fname)
        print(media_dt)
        return {'file':fullpath,'media_dt':media_dt,'duration':duration,'type':media_type}
    else:
        return None

def display_menu(menu):
    while True:
        m={}
        i=0
        for key, value in menu.items():
            i += 1
            m[str(i)]={'filter':key,'path':value}
            print('{i} {title}'.format(i=i,title=key))

        print('{i} {title}'.format(i='q',title='quit'))
        m['q']={'filter':'','path':''}
        sel = input("select ")
        if sel.lower() in m:
            break

    return m[sel.lower()]

def get_media_files(dirname,mdays):
    files = listdir(tolist)
    mlist=[]
    for f in files:
        fullpath = join(dirname,f)
        stat_info = os.stat(fullpath)
        mtime = datetime.fromtimestamp(stat_info.st_mtime)
        if (current_day - mtime).days > maxdays:
            continue
        elif f.startswith('.'):
            continue
        exifobj = get_exif(fullpath)
        if exifobj is not None:
            mlist.append(exifobj)
        mlist2 =  sorted(mlist, key=sortFunction)
    return mlist2
	

	
def get_menu(mlist2):
    menu=[]
    for f in mlist2:
        key = str(f['media_dt']).split( )[0]
        if not key in menu:
            menu.append(key)
    return menu
	
def display_menu2(_menu):
        i=0
        for _m in _menu:
            i += 1
            print('{i} {title}'.format(i=i,title=_m))
        print('{i} {title}'.format(i='n',title='new'))  
        print('{i} {title} {timezone}'.format(i='t',title='timezone',timezone=pd.timezone))   
        print('{i} {title}'.format(i='q',title='quit'))
        _sel = input("select ")
        return _sel.lower()
	   		
def new_project():
    _pdproj = proj_template() 
    with open(outfile, 'w') as _json_file:
        json.dump(_pdproj, _json_file)
        print('{file} saved!'.format(file=outfile)) #
    return open_project()
	   	
def save_project():
    with open(outfile, 'w') as _json_file:
        json.dump(pdproj, _json_file)
        print('{file} saved!'.format(file=outfile)) #
    return open_project()
	   	
	   	
def open_project():
 
    # Opening JSON file
    with open(outfile, encoding='utf-8') as fh:
        _pdproj = json.load(fh)


    #_f = open(outfile)
    # returns JSON object as 
    # a dictionary
    #_pdproj = json.load(_f)
    _medias = _pdproj["tracks"][0]['timelineUnit'] 
    if len(_medias)==0:
            pd.beginUS=0
            pd.endUS=0
            ndt=datetime(2000,1,1)
            pd.lastdt=ndt
    else:
            pd.beginUS=_medias[len(_medias)-1]['endUs']
            _tmp = _medias[len(_medias)-1]
            _x = get_exif( _tmp['timelineClip']['filePath'])
            pd.lastdt=_x['media_dt']
            pd.endUS=_medias[len(_medias)-1]['endUs']
    #print('current begin={begin} end={end}'.format(begin=pd.beginUS,end=pd.endUS))
    print_state()
    # Closing file
    #_f.close()
    return _pdproj

def append_project(datestr):
    for obj in mobjects:
        if obj['media_dt'].strftime('%Y-%m-%d').startswith(datestr):
            if obj['media_dt'] > pd.lastdt:
                print(obj['media_dt'],obj['type'])
                if obj['type']=='i':
                    append_image(obj)
                elif obj['type']=='v':
                    append_video(obj)
            else:
                print(obj['media_dt'],'pass')
    save_project()
    return

def append_image(_m):
    _medias = pdproj["tracks"][0]['timelineUnit']
    pd.endUS= pd.endUS+ img_ms * 1000
    _tmp = image_template()
    _tmp['beginUs']  =  pd.beginUS
    _tmp['endUs'] = pd.endUS
    _tmp['timelineClip']['filePath'] = _m["file"]
    _tmp['timelineClip']['out-tx']['durationUs'] = img_ms * 1000
    _tmp['timelineClip']['audio-tx']['durationUs'] = img_ms * 1000
    _tmp['timelineClip']['width'] = 1920
    _tmp['timelineClip']['height'] = 1080
    _tmp['timelineClip']['inTimeUs']=0
    _tmp['timelineClip']['outTimeUs'] = img_ms * 1000
    _medias.append(_tmp)
    pd.beginUS = pd.endUS
    print(pd.beginUS)
    return
	   	 
def append_video(_m):
	_medias = pdproj["tracks"][0]['timelineUnit']
	video_duration_ms = _m['duration'] 
	pd.endUS= pd.endUS + video_duration_ms * 1000
	tmp = 	video_template()
	tmp['beginUs']  =  pd.beginUS
	tmp['endUs'] = pd.endUS
	tmp['timelineClip']['filePath'] = _m["file"]
	tmp['timelineClip']['audio-tx']['durationUs'] = video_duration_ms  * 1000
	tmp['timelineClip']['width'] = 1920
	tmp['timelineClip']['height'] = 1080
	tmp['timelineClip']['inTimeUs'] =0
	tmp['timelineClip']['outTimeUs'] =video_duration_ms * 1000
	tmp['timelineClip']['originalDurationUs'] =video_duration_ms  * 1000
	_medias.append(tmp)
	pd.beginUS = pd.endUS  	
	print(pd.beginUS)
	return
	   	
def change_time_zone():
    print("input timezone")
    tz=input()
    if tz.isnumeric:
        pd.timezone=tz
    else:
        pd.timezone=8
    return
    	
def print_state():
    print('timezone=',pd.timezone)
    print('project seconds=',pd.endUS/1000000)
    print('last dt=',pd.lastdt)

print(os.name)    	
tolist = MEDIA_PATH()
print('scanning files...')
mobjects=get_media_files(tolist,maxdays)
menus=get_menu(mobjects)
pdproj=open_project()
while True:
	sel=display_menu2(menus)
	print(sel)
	if sel == "q":
		break
	elif sel=='n':
		pdproj = new_project()
	elif sel=='t':
		change_time_zone()
		print_state()
	elif sel.isnumeric:
		if len(menus) >= int(sel):
			print(menus[int(sel)-1])
			append_project(menus[int(sel)-1])
		else:
			print("out of index")
print("program end")
quit()



menu = {}


files = listdir(tolist)
for f in files:
    fullpath = join(tolist,f)
    #mtime=time.ctime(os.path.getmtime(fullpath))
    stat_info = os.stat(fullpath)
    mtime = datetime.fromtimestamp(stat_info.st_mtime)

    
    if (current_day - mtime).days > maxdays:
    	continue
    if os.path.basename(fullpath).startswith("."):
        # hidden file
        pass
    elif isfile(fullpath):
        aa= get_exif(fullpath)
        if aa != None:
            #print(aa['file'])
            #media_list2 =  sorted(media_list, key=sortFunction)
            key = str(aa['media_dt']).split( )[0]
            if not key in menu:
                
                #d2 = datetime.strptime(key,"%Y-%m-%d")
                #dtoday = date.today
                #current_day = datetime.now() 
                #dx = (current_day - d2).days
              
                #if dx<maxdays:
                 menu[key]=tolist
    elif isdir(fullpath):
        key=f
        if not key in menu:
            menu['dir:' + key]= key

pdproj = proj_template() 
medias = pdproj["tracks"][0]['timelineUnit']
#tmp = datetime.now()
#outfile = os.path.join(PDPROJ_PATH(),tmp.strftime('%Y%m%d_%H%M%S') + ".pdrproj")
outfile = os.path.join(PDPROJ_PATH(),"16-9" + ".pdrproj")
while True:
    tolist = MEDIA_PATH()
    m = display_menu(menu)
    if m['path']=='':
        print("program end")
        quit()

    filter=''
    if tolist!=m['path']:
        tolist=os.path.join(tolist ,m['path'])
    else:
        filter=m['filter']

    media_list = []
    print("processing...",tolist)
    #range = input('0-today 1-yesterday others-all') 
    files = listdir(tolist)
    for f in files:
        fullpath = join(tolist,f)
        if os.path.basename(fullpath).startswith("."):
            # hidden file
            pass
        elif isfile(fullpath):
            aa= get_exif(fullpath)
            if aa != None:
                #print(aa['file'])
                key = str(aa['media_dt']).split( )[0]
                if key.startswith(filter):
                    exif = get_exif(fullpath) 
                    if exif != None:
                        media_list.append(exif)


    medias = pdproj["tracks"][0]['timelineUnit']
    if len(medias)==0:
        beginUS=0
        endUs=0
    else:
        beginUS=medias[len(medias)-1]['endUs']
        endUs=medias[len(medias)-1]['endUs']

    img_ms = 2000
    media_list2 =  sorted(media_list, key=sortFunction)

    for  m in media_list2:
        print(m["media_dt"],m["type"])
        if m["type"] == 'i':
            endUs = endUs + img_ms * 1000
            tmp = image_template()
            tmp['beginUs']  =  beginUS
            tmp['endUs'] = endUs
            tmp['timelineClip']['filePath'] = m["file"] 
            tmp['timelineClip']['out-tx']['durationUs'] = img_ms * 1000
            tmp['timelineClip']['audio-tx']['durationUs'] = img_ms * 1000
            tmp['timelineClip']['width'] = 1920
            tmp['timelineClip']['height'] = 1080
            tmp['timelineClip']['inTimeUs'] = 0 
            tmp['timelineClip']['outTimeUs'] = img_ms * 1000
            #tmp['timelineClip']['originalDurationUs'] = video_duration_ms
            medias.append(tmp)
            beginUS = endUs
        elif m['type'] == 'v':
            video_duration_ms = m['duration'] 
            endUs = endUs + video_duration_ms * 1000
            tmp = 	video_template()
            tmp['beginUs']  =  beginUS
            tmp['endUs'] = endUs
            tmp['timelineClip']['filePath'] = m["file"] 
            tmp['timelineClip']['audio-tx']['durationUs'] = video_duration_ms  * 1000
            tmp['timelineClip']['width'] = 1920
            tmp['timelineClip']['height'] = 1080
            tmp['timelineClip']['inTimeUs'] = 0 
            tmp['timelineClip']['outTimeUs'] =video_duration_ms * 1000
            tmp['timelineClip']['originalDurationUs'] =video_duration_ms  * 1000
            medias.append(tmp)
            beginUS = endUs
            pass
        else:
            pass

    #medias.sort(key=lambda x: x['dt'], reverse=True)
    #medias = sorted(medias, key=lambda k: k['page'].get('update_time', 0), reverse=True)

    pdproj["tracks"][0]['timelineUnit'] = medias
    with open(outfile, 'w') as json_file:
        json.dump(pdproj, json_file)
        # now we have a list of medias
        # start create prowerdirector project file

    print('{file} saved!'.format(file=outfile)) # PDPROJ_PATH()))

